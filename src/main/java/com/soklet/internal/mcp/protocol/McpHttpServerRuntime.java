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

import com.soklet.Cors;
import com.soklet.CorsPreflight;
import com.soklet.CorsPreflightResponse;
import com.soklet.CorsResponse;
import com.soklet.HttpMethod;
import com.soklet.MediaRange;
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
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

record McpRequestExecutionSnapshot(int retainedRequestControls,
		int queuedProtocolRequests, int activeRequestIds,
		int activeResponseStreams, long bufferedStreamFrames,
		long bufferedStreamBytes, long terminalStreamBytes,
		int maximumObservedBufferedFramesPerStream,
		int maximumObservedBufferedBytesPerStream,
		long unknownMirroredHeaderOccurrences) {
}

/**
 * Package-private production runtime for the Phase 3 Streamable HTTP slices.
 * It owns a listener that is independent from Soklet's application HTTP
 * server, handles framework-owned discovery, and hands registered operations
 * to the bounded application execution runtime without retaining a protocol
 * request-processing thread.
 */
final class McpHttpServerRuntime implements AutoCloseable {
	private static final String CONTENT_TYPE = "Content-Type";
	private static final String ACCEPT = "Accept";
	private static final String HOST = "Host";
	private static final String ORIGIN = "Origin";
	private static final String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
	private static final String MCP_METHOD = "Mcp-Method";
	private static final String MCP_NAME = "Mcp-Name";
	private static final String CACHE_CONTROL = "Cache-Control";
	private static final String CACHE_CONTROL_NO_STORE = "no-store";
	private static final String RETRY_AFTER = "Retry-After";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String SSE_MEDIA_TYPE = "text/event-stream";
	private static final int SOKLET_RATE_LIMITED = -31999;
	private static final int SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER = -31998;
	private static final Set<String> FRAMEWORK_OWNED_POLICY_HEADERS = Set.of(
			"cache-control", "connection", "content-encoding", "content-length",
			"content-type", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "proxy-connection", "te", "trailer",
			"transfer-encoding", "upgrade", "retry-after");
	private static final Set<HttpMethod> MCP_HTTP_METHODS =
			Set.of(HttpMethod.POST, HttpMethod.OPTIONS);
	private static final Set<String> MCP_PREFLIGHT_REQUEST_HEADERS = Set.of(
			"Accept", "Authorization", "Content-Type", "MCP-Protocol-Version",
			"Mcp-Method", "Mcp-Name");
	private static final Set<String> MCP_EXPOSED_RESPONSE_HEADERS =
			Set.of("WWW-Authenticate");
	private static final byte[] EMPTY_BODY = new byte[0];

	private final McpHttpTransportConfiguration transportConfiguration;
	private final McpHttpEndpointPolicy endpointPolicy;
	private final McpJsonLimits jsonLimits;
	private final McpJsonRpcEnvelopeCodec envelopeCodec;
	private final McpRequestWireMapper requestWireMapper;
	private final McpMirroredHeaderCodec mirroredHeaderCodec;
	private final McpCustomMirroredHeaderValidator customMirroredHeaderValidator;
	private final McpNormalizedEndpoint endpoint;
	private final McpServerCapabilityRegistry capabilityRegistry;
	private final McpApplicationRequestRouter applicationRouter;
	private final McpApplicationExecutionConfiguration applicationConfiguration;
	private final McpApplicationClock applicationClock;
	private final McpApplicationHandlerExecutorFactory applicationExecutorFactory;
	private final Object lifecycleLock;
	private final Map<MicrohttpRequest, RequestControl> requestControls;
	private final ConcurrentHashMap<McpJsonRpcId, RequestControl> activeRequestIds;
	private final AtomicLong processorThreadSequence;
	private final AtomicLong unknownMirroredHeaderOccurrences;
	private LifecycleState lifecycleState;
	private @Nullable EventLoop eventLoop;
	private @Nullable EventLoop residualEventLoop;
	private @Nullable ThreadPoolExecutor requestProcessor;
	private @Nullable ThreadPoolExecutor residualRequestProcessor;
	private @Nullable McpApplicationExecution applicationExecution;
	private @Nullable McpApplicationExecution residualApplicationExecution;
	private @Nullable InetSocketAddress boundAddress;
	private @Nullable AtomicReference<ListenerState> currentReadiness;

	McpHttpServerRuntime(McpHttpTransportConfiguration transportConfiguration,
			McpHttpEndpointPolicy endpointPolicy, McpNormalizedEndpoint endpoint) {
		this(transportConfiguration, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(), McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(McpHttpTransportConfiguration transportConfiguration,
			McpHttpEndpointPolicy endpointPolicy, McpNormalizedEndpoint endpoint,
			McpJsonLimits jsonLimits) {
		this(transportConfiguration, endpointPolicy, endpoint, jsonLimits,
				McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(McpHttpTransportConfiguration transportConfiguration,
			McpHttpEndpointPolicy endpointPolicy, McpNormalizedEndpoint endpoint,
			McpApplicationRequestRouter applicationRouter,
			McpApplicationExecutionConfiguration applicationConfiguration,
			McpApplicationClock applicationClock) {
		this(transportConfiguration, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(), applicationRouter,
				applicationConfiguration, applicationClock,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(McpHttpTransportConfiguration transportConfiguration,
			McpHttpEndpointPolicy endpointPolicy, McpNormalizedEndpoint endpoint,
			McpJsonLimits jsonLimits, McpApplicationRequestRouter applicationRouter,
			McpApplicationExecutionConfiguration applicationConfiguration,
			McpApplicationClock applicationClock,
			McpApplicationHandlerExecutorFactory applicationExecutorFactory) {
		this.transportConfiguration = requireNonNull(transportConfiguration);
		this.endpointPolicy = requireNonNull(endpointPolicy);
		this.jsonLimits = requireNonNull(jsonLimits);
		this.applicationRouter = requireNonNull(applicationRouter);
		this.applicationConfiguration = requireNonNull(applicationConfiguration);
		this.applicationClock = requireNonNull(applicationClock);
		this.applicationExecutorFactory = requireNonNull(applicationExecutorFactory);
		validateConfiguredAllowedHosts();

		if (transportConfiguration.maximumRequestBodyBytes()
				> jsonLimits.maximumInputBytes())
			throw new IllegalArgumentException("The HTTP request-body limit must not exceed "
					+ "the strict JSON input limit.");

		McpJsonCodec jsonCodec = new McpJsonCodec(jsonLimits);
		this.envelopeCodec = new McpJsonRpcEnvelopeCodec(jsonCodec);
		this.requestWireMapper = new McpRequestWireMapper(jsonLimits);
		this.mirroredHeaderCodec = new McpMirroredHeaderCodec(Math.min(
				McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES,
				transportConfiguration.maximumHeaderBytes()));
		this.customMirroredHeaderValidator =
				new McpCustomMirroredHeaderValidator(mirroredHeaderCodec);
		this.endpoint = requireNonNull(endpoint);
		this.capabilityRegistry = McpServerCapabilityRegistry.fromEndpoint(endpoint);
		this.lifecycleLock = new Object();
		this.requestControls = Collections.synchronizedMap(new IdentityHashMap<>());
		this.activeRequestIds = new ConcurrentHashMap<>();
		this.processorThreadSequence = new AtomicLong();
		this.unknownMirroredHeaderOccurrences = new AtomicLong();
		this.lifecycleState = LifecycleState.STOPPED;
	}

	InetSocketAddress start() throws IOException {
		synchronized (lifecycleLock) {
			if (lifecycleState != LifecycleState.STOPPED)
				throw new IllegalStateException("The MCP HTTP server is not stopped.");
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

			lifecycleState = LifecycleState.STARTING;
			ThreadPoolExecutor candidateProcessor = null;
			McpApplicationExecution candidateApplicationExecution = null;
			AtomicReference<ListenerState> candidateReadiness =
					new AtomicReference<>(ListenerState.STARTING);
			AtomicReference<InetSocketAddress> candidateAddress = new AtomicReference<>();
			EventLoop candidateEventLoop = null;

			try {
				candidateProcessor = newRequestProcessor();
				candidateApplicationExecution = new McpApplicationExecution(
						applicationConfiguration, applicationClock,
						applicationExecutorFactory, this::runProtocolDeadlineCycle,
						endpointPolicy.requestInterceptor());
				ThreadPoolExecutor readyProcessor = candidateProcessor;
				McpApplicationExecution readyApplicationExecution =
						candidateApplicationExecution;
				Handler handler = new Handler() {
					@Override
					public void handle(MicrohttpRequest request,
							Consumer<MicrohttpResponse> callback) {
						if (candidateReadiness.get() != ListenerState.READY) {
							callback.accept(emptyResponse(503, "Service Unavailable", List.of()));
							return;
						}

						submitRequest(readyProcessor, readyApplicationExecution,
							candidateAddress.get(), request, callback);
					}

					@Override
					public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
						return true;
					}

					@Override
					public boolean monitorClientDisconnectsDuringStreamingResponse(
							MicrohttpRequest request) {
						return true;
					}

					@Override
					public void cancel(MicrohttpRequest request,
							StreamTerminationReason reason,
							@Nullable Throwable cause) {
						cancelRequest(request, reason, cause);
					}
				};
				Options options = microhttpOptions();
				candidateEventLoop = new EventLoop(options, NoopLogger.instance(), handler,
						connectionListener(candidateReadiness));
				InetSocketAddress effectiveAddress = candidateEventLoop.getLocalAddress();
				candidateAddress.set(effectiveAddress);
				candidateEventLoop.start();
				candidateApplicationExecution.start();

				this.requestProcessor = candidateProcessor;
				this.applicationExecution = candidateApplicationExecution;
				this.eventLoop = candidateEventLoop;
				this.boundAddress = effectiveAddress;
				this.currentReadiness = candidateReadiness;
				this.lifecycleState = LifecycleState.STARTED;
				if (!candidateReadiness.compareAndSet(
						ListenerState.STARTING, ListenerState.READY))
					throw new IOException("The MCP HTTP listener terminated during startup.");
				return effectiveAddress;
			} catch (IOException | RuntimeException | Error throwable) {
				candidateReadiness.set(ListenerState.TERMINATED);
				closeFailedStart(candidateEventLoop, candidateProcessor,
						candidateApplicationExecution);
				this.requestProcessor = null;
				this.applicationExecution = null;
				this.eventLoop = null;
				this.boundAddress = null;
				this.currentReadiness = null;
				this.lifecycleState = LifecycleState.STOPPED;
				throw throwable;
			}
		}
	}

	void stop() {
		EventLoop eventLoopToStop;
		ThreadPoolExecutor processorToStop;
		McpApplicationExecution applicationToStop;
		boolean interrupted = false;

		synchronized (lifecycleLock) {
			while (lifecycleState == LifecycleState.STOPPING) {
				try {
					lifecycleLock.wait();
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			if (lifecycleState == LifecycleState.STOPPED) {
				if (interrupted)
					Thread.currentThread().interrupt();
				return;
			}

			if (lifecycleState != LifecycleState.STARTED
					&& lifecycleState != LifecycleState.FAILED)
				throw new IllegalStateException("The MCP HTTP server cannot stop from state "
						+ lifecycleState + ".");

			lifecycleState = LifecycleState.STOPPING;
			if (currentReadiness != null)
				currentReadiness.set(ListenerState.TERMINATED);
			eventLoopToStop = requireNonNull(eventLoop);
			processorToStop = requireNonNull(requestProcessor);
			applicationToStop = requireNonNull(applicationExecution);
		}

		boolean eventLoopTerminated = false;
		boolean applicationTerminated = false;
		try {
			long shutdownStartedAt = System.nanoTime();
			long shutdownTimeoutNanos = transportConfiguration.shutdownTimeout().toNanos();
			eventLoopToStop.stopAccepting();
			// Close application admission and atomically drain its queue before
			// interrupting active work. Otherwise an interrupted active handler can
			// promote queued application code during shutdown.
			applicationToStop.stop();
			cancelAllRequests(StreamTerminationReason.SERVER_STOPPING, null);
			eventLoopToStop.stop();
			processorToStop.shutdownNow();
			cancelAllRequests(StreamTerminationReason.SERVER_STOPPING, null);

			while (!eventLoopTerminated) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;
				try {
					eventLoopTerminated = eventLoopToStop.join(
							Duration.ofNanos(remainingNanos));
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			while (!processorToStop.isTerminated()) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;

				try {
					processorToStop.awaitTermination(
							remainingNanos, TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			while (!applicationTerminated) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;
				try {
					applicationTerminated = applicationToStop.awaitTermination(
							Duration.ofNanos(remainingNanos));
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
		} finally {
			synchronized (lifecycleLock) {
				eventLoop = null;
				residualEventLoop = eventLoopTerminated || eventLoopToStop.isTerminated()
						? null : eventLoopToStop;
				requestProcessor = null;
				residualRequestProcessor = processorToStop.isTerminated()
						? null : processorToStop;
				applicationExecution = null;
				residualApplicationExecution = applicationTerminated
						|| applicationToStop.isTerminated() ? null : applicationToStop;
				boundAddress = null;
				currentReadiness = null;
				lifecycleState = LifecycleState.STOPPED;
				lifecycleLock.notifyAll();
			}

			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private long remainingShutdownNanos(long shutdownStartedAt,
			long shutdownTimeoutNanos) {
		long elapsedNanos = System.nanoTime() - shutdownStartedAt;
		return elapsedNanos >= shutdownTimeoutNanos
				? 0L : shutdownTimeoutNanos - elapsedNanos;
	}

	private long saturatingAdd(long left, long right) {
		long result = left + right;
		return ((left ^ result) & (right ^ result)) < 0
				? Long.MAX_VALUE : result;
	}

	boolean isStarted() {
		synchronized (lifecycleLock) {
			return lifecycleState == LifecycleState.STARTED;
		}
	}

	Optional<InetSocketAddress> boundAddress() {
		synchronized (lifecycleLock) {
			return lifecycleState == LifecycleState.STARTED
					? Optional.of(requireNonNull(boundAddress))
					: Optional.empty();
		}
	}

	Optional<McpApplicationExecutionSnapshot> applicationExecutionSnapshot() {
		synchronized (lifecycleLock) {
			McpApplicationExecution execution = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			return execution == null ? Optional.empty()
					: Optional.of(execution.snapshot(activeRequestIds.size()));
		}
	}

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
					activeRequestIds.size(), activeStreams, bufferedFrames,
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

	private ThreadPoolExecutor newRequestProcessor() {
		int concurrency = transportConfiguration.requestProcessorConcurrency();
		ThreadFactory threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "soklet-mcp-request-"
					+ processorThreadSequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
		return new ThreadPoolExecutor(
				concurrency,
				concurrency,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(
						transportConfiguration.requestProcessorQueueCapacity()),
				threadFactory,
				new ThreadPoolExecutor.AbortPolicy());
	}

	private ConnectionListener connectionListener(
			AtomicReference<ListenerState> readiness) {
		return new ConnectionListener() {
			@Override
			public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didTerminateEventLoop(EventLoop terminatedEventLoop,
					Throwable throwable) {
				ListenerState previous = readiness.getAndSet(ListenerState.TERMINATED);
				if (previous == ListenerState.READY)
					handleUnexpectedTermination(terminatedEventLoop);
			}
		};
	}

	private void handleUnexpectedTermination(EventLoop terminatedEventLoop) {
		ThreadPoolExecutor processorToStop = null;
		McpApplicationExecution applicationToStop = null;

		synchronized (lifecycleLock) {
			if (eventLoop != terminatedEventLoop || lifecycleState != LifecycleState.STARTED)
				return;

			lifecycleState = LifecycleState.FAILED;
			boundAddress = null;
			processorToStop = requestProcessor;
			applicationToStop = applicationExecution;
		}

		if (applicationToStop != null)
			applicationToStop.stop(StreamTerminationReason.INTERNAL_ERROR);
		cancelAllRequests(StreamTerminationReason.INTERNAL_ERROR, null);
		if (processorToStop != null)
			processorToStop.shutdownNow();
		cancelAllRequests(StreamTerminationReason.INTERNAL_ERROR, null);
	}

	private void submitRequest(ThreadPoolExecutor processor,
			McpApplicationExecution application,
			@Nullable InetSocketAddress effectiveAddress, MicrohttpRequest request,
			Consumer<MicrohttpResponse> callback) {
		requireNonNull(request);
		requireNonNull(callback);

		if (effectiveAddress == null) {
			callback.accept(emptyResponse(503, "Service Unavailable", List.of()));
			return;
		}

		// nanoTime may wrap; every comparison uses subtraction and the configured
		// positive duration is constrained to the signed nanosecond range.
		long deadlineNanos = applicationClock.nanoTime()
				+ applicationConfiguration.requestDeadline().toNanos();
		RequestControl requestControl = new RequestControl(request, deadlineNanos,
				processor, application, callback);
		FutureTask<Void> task = new FutureTask<>(() -> {
			MicrohttpResponse response = processRequest(effectiveAddress, request,
					requestControl, application);
			requestControl.completeProtocol(response);
			return null;
		});
		requestControl.submit(task);
	}

	private void cancelRequest(MicrohttpRequest request,
			StreamTerminationReason reason, @Nullable Throwable cause) {
		RequestControl requestControl = requestControls.get(request);
		if (requestControl != null)
			requestControl.cancel(reason, cause);
	}

	private void cancelAllRequests(StreamTerminationReason reason,
			@Nullable Throwable cause) {
		List<RequestControl> controls;
		synchronized (requestControls) {
			controls = List.copyOf(requestControls.values());
		}
		for (RequestControl control : controls)
			control.cancel(reason, cause);
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

	private @Nullable MicrohttpResponse processRequest(InetSocketAddress effectiveAddress,
			MicrohttpRequest request, RequestControl requestControl,
			McpApplicationExecution application) {
		try {
			return processRequestSafely(effectiveAddress, request,
					requestControl, application);
		} catch (Throwable throwable) {
			return emptyResponse(500, "Internal Server Error", List.of());
		}
	}

	private @Nullable MicrohttpResponse processRequestSafely(
			InetSocketAddress effectiveAddress, MicrohttpRequest request,
			RequestControl requestControl,
			McpApplicationExecution application) {
		if (!requestControl.protocolProcessingAllowed())
			return null;

		if (request.contentTooLarge()
				|| request.body().length > transportConfiguration.maximumRequestBodyBytes())
			return emptyResponse(413, "Content Too Large", List.of());

		if (!"HTTP/1.1".equals(request.version()))
			return emptyResponse(505, "HTTP Version Not Supported", List.of());

		if (!endpointPolicy.path().equals(requestPath(request.uri())))
			return emptyResponse(404, "Not Found", List.of());

		if (!authorizedHost(effectiveAddress, request))
			return emptyResponse(421, "Misdirected Request", List.of());

		MicrohttpResponse originPolicyFailure = prevalidateOriginPolicy(request);
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

		Request sokletRequest = toSokletRequest(request, httpMethod.orElseThrow());
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (httpMethod.orElseThrow() == HttpMethod.OPTIONS)
			return processPreflight(request, sokletRequest);

		CorsAuthorization corsAuthorization = authorizeCors(request, sokletRequest,
				httpMethod.orElseThrow());
		if (corsAuthorization.rejection().isPresent())
			return corsAuthorization.rejection().orElseThrow();

		List<Header> corsHeaders = corsAuthorization.response()
				.map(response -> corsHeaders(request, response))
				.orElseGet(List::of);
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
			return wireDecodingFailure(exception, null, corsHeaders);
		}

		if (envelope instanceof McpJsonRpcEnvelope.Notification notification)
			return processNotification(request, sokletRequest, notification,
					corsHeaders, requestControl);

		if (!(envelope instanceof McpJsonRpcEnvelope.Request wireRequest))
			return jsonRpcError(400, "Bad Request", Optional.empty(),
					new McpJsonRpcError(McpJsonRpcError.INVALID_REQUEST,
							"Invalid Request", Optional.empty()), corsHeaders);

		MicrohttpResponse mirroredHeaderFailure = validateRequiredMirroredHeaders(
				request, wireRequest, corsHeaders);
		if (mirroredHeaderFailure != null)
			return mirroredHeaderFailure;

		McpCustomMirroredHeaderValidation customHeaderValidation =
				customMirroredHeaderValidator.validate(request.headers(), wireRequest,
						capabilityRegistry,
						endpointPolicy.unknownMirroredHeaderPolicy());
		recordUnknownMirroredHeaders(customHeaderValidation.unknownHeaderCount());
		if (customHeaderValidation.outcome()
				== McpCustomMirroredHeaderOutcome.HEADER_MISMATCH)
			return headerMismatch(wireRequest.id(), corsHeaders);
		if (customHeaderValidation.outcome()
				== McpCustomMirroredHeaderOutcome.STRICT_UNKNOWN)
			return strictUnknownMirroredHeader(wireRequest.id(), corsHeaders);

		McpJsonRpcMessage.Request mappedRequest;
		try {
			mappedRequest = requestWireMapper.map(wireRequest);
		} catch (McpWireDecodingException exception) {
			return wireDecodingFailure(exception, wireRequest.method(), corsHeaders);
		}

		String headerProtocolVersion = singleHeader(request, MCP_PROTOCOL_VERSION)
				.orElseThrow();
		if (!headerProtocolVersion.equals(mappedRequest.params().metadata().protocolVersion()))
			return headerMismatch(mappedRequest.id(), corsHeaders);

		String requestedProtocolVersion = mappedRequest.params().metadata().protocolVersion();
		if (!McpProtocolVersion.SUPPORTED.contains(requestedProtocolVersion))
			return jsonRpcError(400, "Bad Request", Optional.of(mappedRequest.id()),
					McpJsonRpcError.unsupportedProtocolVersion(requestedProtocolVersion),
					corsHeaders);

		RequestIdRegistration idRegistration =
				requestControl.registerRequestId(mappedRequest.id());
		if (idRegistration == RequestIdRegistration.TERMINATED)
			return null;
		if (idRegistration == RequestIdRegistration.DUPLICATE) {
			application.recordDuplicateIdRejection();
			return applicationResponse(
					McpApplicationResponse.duplicateRequestId(mappedRequest.id()),
					mappedRequest.id(), corsHeaders);
		}

		boolean discoveryRequest = "server/discover".equals(mappedRequest.method());
		Optional<McpApplicationRequestHandler> applicationHandler =
				applicationRouter.resolve(mappedRequest.method());
		if (!discoveryRequest && applicationHandler.isEmpty())
			return methodNotFound(mappedRequest, corsHeaders);

		if (discoveryRequest && !mappedRequest.params().fields().members().isEmpty())
			return jsonRpcError(400, "Bad Request", Optional.of(mappedRequest.id()),
					new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
							"Invalid params", Optional.empty()), corsHeaders);

		if (!requestControl.protocolProcessingAllowed())
			return null;
		McpAdmissionContext admissionContext = new McpAdmissionContext(
				sokletRequest, endpoint, Map.of(), mappedRequest.method(), false,
				Optional.of(mappedRequest.id()), requestedProtocolVersion,
				Optional.empty(), mappedRequest.params().metadata().clientInformation(),
				Optional.of(mappedRequest.params().metadata().clientCapabilities()));
		McpAdmissionDecision admissionDecision;
		try {
			admissionDecision = endpointPolicy.requestAdmissionPolicy().admit(admissionContext);
		} catch (Throwable throwable) {
			return policyHookInternalError(mappedRequest.id(), corsHeaders);
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (admissionDecision == null)
			return policyHookInternalError(mappedRequest.id(), corsHeaders);

		if (admissionDecision instanceof McpAdmissionDecision.Rejected rejected) {
			try {
				return admissionRejection(mappedRequest.id(), rejected.rejection(), corsHeaders);
			} catch (IllegalArgumentException exception) {
				return policyHookInternalError(mappedRequest.id(), corsHeaders);
			}
		}
		McpAdmissionIdentity admittedIdentity =
				((McpAdmissionDecision.Accepted) admissionDecision).identity();
		McpEffectiveAdmissionIdentity effectiveIdentity =
				McpEffectiveAdmissionIdentity.resolve(endpoint, endpointPolicy.path(),
						admittedIdentity);
		if (!requestControl.protocolProcessingAllowed())
			return null;

		if (endpointPolicy.requestRateLimiter().isPresent()) {
			McpRateLimitDecision rateLimitDecision;
			try {
				rateLimitDecision = endpointPolicy.requestRateLimiter().orElseThrow().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, effectiveIdentity,
								McpRateLimitTarget.REQUEST, mappedRequest.method(),
								Optional.empty()));
			} catch (Throwable throwable) {
				return policyHookInternalError(mappedRequest.id(), corsHeaders);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitDecision == null)
				return policyHookInternalError(mappedRequest.id(), corsHeaders);
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied)
				return rateLimited(mappedRequest.id(), denied.retryAfter(), corsHeaders);
		}

		if (discoveryRequest) {
			McpJsonRpcMessage.ResultResponse response = new McpJsonRpcMessage.ResultResponse(
					mappedRequest.id(), capabilityRegistry.discoverResult().toWireResult(),
					McpJsonObject.empty());
			return jsonResponse(200, "OK", envelopeCodec.encode(response), corsHeaders);
		}

		requestControl.handoff(application, () -> application.dispatch(
				request, mappedRequest, effectiveIdentity,
				applicationHandler.orElseThrow(),
				requestControl.deadlineNanos(),
				new McpApplicationResponseWriter() {
					@Override
					public boolean write(McpApplicationResponse response) {
						return requestControl.writeApplicationResponse(response,
								mappedRequest.id(), corsHeaders);
					}

					@Override
					public boolean writeNotification(
							McpJsonRpcMessage.Notification notification)
							throws InterruptedException {
						return requestControl.writeApplicationNotification(
								notification, corsHeaders);
					}
				},
				requestControl::applicationTerminated));
		return null;
	}

	private void recordUnknownMirroredHeaders(int occurrences) {
		if (occurrences == 0)
			return;
		unknownMirroredHeaderOccurrences.getAndUpdate(current ->
				current > Long.MAX_VALUE - occurrences
						? Long.MAX_VALUE : current + occurrences);
	}

	private @Nullable MicrohttpResponse processNotification(MicrohttpRequest request,
			Request sokletRequest, McpJsonRpcEnvelope.Notification notification,
			List<Header> corsHeaders, RequestControl requestControl) {
		boolean cancellationNotification =
				"notifications/cancelled".equals(notification.method());
		if (!cancellationNotification && !validPresentNotificationMetadata(notification))
			return emptyResponse(400, "Bad Request", corsHeaders);

		List<String> protocolVersions = headerValues(request, MCP_PROTOCOL_VERSION);
		if (protocolVersions.size() != 1)
			return emptyResponse(400, "Bad Request", corsHeaders);
		String protocolVersion = protocolVersions.get(0);
		try {
			mirroredHeaderCodec.requirePlainString(protocolVersion);
		} catch (IllegalArgumentException exception) {
			return emptyResponse(400, "Bad Request", corsHeaders);
		}
		if (!McpProtocolVersion.SUPPORTED.contains(protocolVersion))
			return emptyResponse(400, "Bad Request", corsHeaders);

		if (!requestControl.protocolProcessingAllowed())
			return null;
		McpAdmissionContext admissionContext = new McpAdmissionContext(
				sokletRequest, endpoint, Map.of(), notification.method(), true,
				Optional.empty(), protocolVersion, Optional.empty(), Optional.empty(),
				Optional.empty());
		McpAdmissionDecision admissionDecision;
		try {
			admissionDecision = endpointPolicy.requestAdmissionPolicy().admit(admissionContext);
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

		if (endpointPolicy.requestRateLimiter().isPresent()) {
			McpRateLimitDecision rateLimitDecision;
			try {
				rateLimitDecision = endpointPolicy.requestRateLimiter().orElseThrow().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, effectiveIdentity,
								McpRateLimitTarget.REQUEST, notification.method(),
								Optional.empty()));
			} catch (Throwable throwable) {
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitDecision == null)
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied)
				return notificationRateLimited(denied.retryAfter(), corsHeaders);
		}

		return cancellationNotification
				? emptyResponse(202, "Accepted", corsHeaders)
				: emptyResponse(400, "Bad Request", corsHeaders);
	}

	private boolean validPresentNotificationMetadata(
			McpJsonRpcEnvelope.Notification notification) {
		Optional<McpJsonValue> params = notification.params();
		if (params.isEmpty() || !(params.orElseThrow() instanceof McpJsonObject object)
				|| !object.members().containsKey("_meta"))
			return true;

		McpJsonValue metadataValue = object.members().get("_meta");
		if (!(metadataValue instanceof McpJsonObject metadata))
			return false;

		try {
			McpProtocolSupport.requireInboundMetadataFields(metadata, Set.of());
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private MicrohttpResponse applicationResponse(McpApplicationResponse response,
			McpJsonRpcId requestId, List<Header> additionalHeaders) {
		requireNonNull(response);
		requireNonNull(requestId);
		requireNonNull(additionalHeaders);

		MicrohttpResponse httpResponse;
		try {
			httpResponse = response.message()
					.map(message -> jsonResponse(response.status(), response.reason(),
							envelopeCodec.encode(message), additionalHeaders))
					.orElseGet(() -> emptyResponse(
							response.status(), response.reason(), additionalHeaders));
		} catch (Throwable throwable) {
			McpJsonRpcError error = new McpJsonRpcError(
					McpJsonRpcError.INTERNAL_ERROR, "Internal error", Optional.empty());
			httpResponse = jsonRpcError(500, "Internal Server Error",
					Optional.of(requestId), error, additionalHeaders);
		}

		return httpResponse;
	}

	private MicrohttpResponse policyHookInternalError(McpJsonRpcId requestId,
			List<Header> corsHeaders) {
		return jsonRpcError(500, "Internal Server Error", Optional.of(requestId),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()), corsHeaders);
	}

	private MicrohttpResponse admissionRejection(McpJsonRpcId requestId,
			McpRequestRejection rejection, List<Header> corsHeaders) {
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

	private MicrohttpResponse notificationAdmissionRejection(
			McpRequestRejection rejection, List<Header> corsHeaders) {
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

	private MicrohttpResponse rateLimited(McpJsonRpcId requestId,
			Duration retryAfter, List<Header> corsHeaders) {
		requireNonNull(retryAfter);
		if (retryAfter.isNegative())
			throw new IllegalArgumentException("Retry-After must not be negative.");
		List<Header> headers = new ArrayList<>(corsHeaders.size() + 1);
		headers.addAll(corsHeaders);
		headers.add(new Header(RETRY_AFTER, retryAfterSeconds(retryAfter)));
		return jsonRpcError(429, "Too Many Requests", Optional.of(requestId),
				new McpJsonRpcError(SOKLET_RATE_LIMITED, "Rate limited", Optional.empty()),
				List.copyOf(headers));
	}

	private MicrohttpResponse notificationRateLimited(Duration retryAfter,
			List<Header> corsHeaders) {
		requireNonNull(retryAfter);
		if (retryAfter.isNegative())
			throw new IllegalArgumentException("Retry-After must not be negative.");
		List<Header> headers = new ArrayList<>(corsHeaders.size() + 1);
		headers.addAll(corsHeaders);
		headers.add(new Header(RETRY_AFTER, retryAfterSeconds(retryAfter)));
		return emptyResponse(429, "Too Many Requests", List.copyOf(headers));
	}

	private String retryAfterSeconds(Duration retryAfter) {
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

	private List<Header> validatedPolicyHeaders(
			Map<String, List<String>> policyHeaders) {
		List<Header> headers = new ArrayList<>();
		Set<String> normalizedNames = new LinkedHashSet<>();
		long encodedBytes = 0L;
		for (Map.Entry<String, List<String>> entry : policyHeaders.entrySet()) {
			String name = requireNonNull(entry.getKey());
			String lowerName = name.toLowerCase(Locale.ROOT);
			if (!validHeaderName(name)
					|| !normalizedNames.add(lowerName)
					|| FRAMEWORK_OWNED_POLICY_HEADERS.contains(lowerName)
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

	private boolean validHeaderName(String name) {
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

	private boolean validHeaderValue(String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '\t' && (character < 0x20 || character > 0x7E))
				return false;
		}
		return true;
	}

	private MicrohttpResponse processPreflight(MicrohttpRequest request,
			Request sokletRequest) {
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
						mcpPreflightRequestHeaders()))
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
				|| !validCorsAllowedHeaders(allowedHeaders))
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

	private boolean validCorsAllowedHeaders(Set<String> allowedHeaders) {
		Set<String> normalizedNames = new LinkedHashSet<>();
		for (String name : allowedHeaders) {
			if (!validHeaderName(name)
					|| !containsOnlyIgnoreCase(Set.of(name), mcpPreflightRequestHeaders())
					|| !normalizedNames.add(name.toLowerCase(Locale.ROOT)))
				return false;
		}
		return true;
	}

	private Set<String> mcpPreflightRequestHeaders() {
		Set<String> headers = new LinkedHashSet<>(MCP_PREFLIGHT_REQUEST_HEADERS);
		headers.addAll(capabilityRegistry.customMirroredHeaderNames());
		return Set.copyOf(headers);
	}

	private CorsAuthorization authorizeCors(MicrohttpRequest request,
			Request sokletRequest, HttpMethod httpMethod) {
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

	private @Nullable MicrohttpResponse prevalidateOriginPolicy(MicrohttpRequest request) {
		List<String> origins = headerValues(request, ORIGIN);
		if (origins.isEmpty())
			return endpointPolicy.absentOriginPolicy() == McpAbsentOriginPolicy.REQUIRE_ORIGIN
					? emptyResponse(403, "Forbidden", List.of()) : null;

		return origins.size() == 1 && validOrigin(origins.get(0))
				? null : emptyResponse(403, "Forbidden", List.of());
	}

	private List<Header> corsHeaders(MicrohttpRequest request, CorsResponse response) {
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

	private Optional<String> safeAllowedOrigin(String requestOrigin,
			String configuredAllowedOrigin, @Nullable Boolean allowCredentials) {
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

	private MicrohttpResponse contentNegotiationFailure(MicrohttpRequest request,
			List<Header> corsHeaders) {
		List<String> contentTypes = headerValues(request, CONTENT_TYPE);
		if (contentTypes.size() > 1)
			return emptyResponse(400, "Bad Request", corsHeaders);

		if (contentTypes.size() != 1 || !isJsonContentType(contentTypes.get(0)))
			return emptyResponse(415, "Unsupported Media Type", corsHeaders);

		if (!acceptsBothResponseTypes(headerValues(request, ACCEPT)))
			return emptyResponse(406, "Not Acceptable", corsHeaders);

		return null;
	}

	private boolean isJsonContentType(String contentType) {
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

	private boolean acceptsBothResponseTypes(List<String> acceptHeaders) {
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

	private boolean validAcceptFragment(String fragment) {
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

	private boolean validQualityValue(String value) {
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

	private BigDecimal effectiveQuality(List<MediaRange> ranges, String type, String subtype) {
		return ranges.stream()
				.filter(range -> range.getParameters().isEmpty())
				.filter(range -> mediaRangeMatches(range, type, subtype))
				.max(Comparator.comparingInt(this::mediaRangeSpecificity)
						.thenComparing(MediaRange::getQuality))
				.map(MediaRange::getQuality)
				.orElse(BigDecimal.ZERO);
	}

	private boolean mediaRangeMatches(MediaRange range, String type, String subtype) {
		return ("*".equals(range.getType()) || type.equals(range.getType()))
				&& ("*".equals(range.getSubtype()) || subtype.equals(range.getSubtype()));
	}

	private int mediaRangeSpecificity(MediaRange range) {
		if ("*".equals(range.getType()))
			return 0;
		if ("*".equals(range.getSubtype()))
			return 1;
		return 2;
	}

	private MicrohttpResponse validateRequiredMirroredHeaders(MicrohttpRequest request,
			McpJsonRpcEnvelope.Request wireRequest, List<Header> corsHeaders) {
		List<String> protocolVersions = headerValues(request, MCP_PROTOCOL_VERSION);
		List<String> methods = headerValues(request, MCP_METHOD);
		List<String> names = headerValues(request, MCP_NAME);

		if (protocolVersions.size() != 1 || methods.size() != 1)
			return headerMismatch(wireRequest.id(), corsHeaders);
		try {
			mirroredHeaderCodec.requirePlainString(protocolVersions.get(0));
			mirroredHeaderCodec.requirePlainString(methods.get(0));
		} catch (IllegalArgumentException exception) {
			return headerMismatch(wireRequest.id(), corsHeaders);
		}
		if (!methods.get(0).equals(wireRequest.method()))
			return headerMismatch(wireRequest.id(), corsHeaders);

		Optional<String> expectedName = standardMirroredName(wireRequest);
		if (requiresMcpName(wireRequest.method())) {
			if (names.size() != 1 || expectedName.isEmpty())
				return headerMismatch(wireRequest.id(), corsHeaders);

			String decodedName;
			try {
				decodedName = mirroredHeaderCodec.decodeString(names.get(0));
			} catch (IllegalArgumentException exception) {
				return headerMismatch(wireRequest.id(), corsHeaders);
			}
			if (!decodedName.equals(expectedName.orElseThrow()))
				return headerMismatch(wireRequest.id(), corsHeaders);
		} else if (!names.isEmpty()) {
			return headerMismatch(wireRequest.id(), corsHeaders);
		}

		return null;
	}

	private boolean requiresMcpName(String method) {
		return "tools/call".equals(method)
				|| "prompts/get".equals(method)
				|| "resources/read".equals(method);
	}

	private Optional<String> standardMirroredName(
			McpJsonRpcEnvelope.Request wireRequest) {
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

	private MicrohttpResponse headerMismatch(McpJsonRpcId id, List<Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.HEADER_MISMATCH,
						"Header mismatch", Optional.empty()), corsHeaders);
	}

	private MicrohttpResponse strictUnknownMirroredHeader(McpJsonRpcId id,
			List<Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER,
						"Unknown mirrored header", Optional.empty()), corsHeaders);
	}

	private MicrohttpResponse methodNotFound(McpJsonRpcMessage.Request request,
			List<Header> corsHeaders) {
		Optional<McpJsonValue> data = "initialize".equals(request.method())
				? Optional.of(supportedVersionDiagnostic())
				: Optional.empty();
		return jsonRpcError(404, "Not Found", Optional.of(request.id()),
				new McpJsonRpcError(McpJsonRpcError.METHOD_NOT_FOUND,
						"Method not found", data), corsHeaders);
	}

	private MicrohttpResponse wireDecodingFailure(McpWireDecodingException exception,
			@Nullable String readableMethod, List<Header> corsHeaders) {
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
		Optional<McpJsonValue> data = "initialize".equals(readableMethod)
				? Optional.of(supportedVersionDiagnostic())
				: Optional.empty();
		return jsonRpcError(400, "Bad Request", exception.readableRequestId(),
				new McpJsonRpcError(code, message, data), corsHeaders);
	}

	private McpJsonObject supportedVersionDiagnostic() {
		List<McpJsonValue> versions = McpProtocolVersion.SUPPORTED.stream()
				.map(McpJsonString::new)
				.map(McpJsonValue.class::cast)
				.toList();
		return new McpJsonObject(Map.of("supportedVersions", new McpJsonArray(versions)));
	}

	private MicrohttpResponse jsonRpcError(int status, String reason,
			Optional<McpJsonRpcId> id, McpJsonRpcError error,
			List<Header> additionalHeaders) {
		McpJsonRpcMessage.ErrorResponse response = new McpJsonRpcMessage.ErrorResponse(
				id, error, McpJsonObject.empty());
		return jsonResponse(status, reason, envelopeCodec.encode(response), additionalHeaders);
	}

	private MicrohttpResponse jsonResponse(int status, String reason, byte[] body,
			List<Header> additionalHeaders) {
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 2);
		headers.add(new Header(CONTENT_TYPE, JSON_MEDIA_TYPE));
		headers.addAll(additionalHeaders);
		return response(status, reason, headers, body);
	}

	private MicrohttpResponse methodNotAllowed(List<Header> additionalHeaders) {
		List<Header> headers = new ArrayList<>(additionalHeaders);
		headers.add(new Header("Allow", "POST, OPTIONS"));
		return emptyResponse(405, "Method Not Allowed", headers);
	}

	private MicrohttpResponse emptyResponse(int status, String reason,
			List<Header> additionalHeaders) {
		return response(status, reason, additionalHeaders, EMPTY_BODY);
	}

	private MicrohttpResponse response(int status, String reason,
			List<Header> additionalHeaders, byte[] body) {
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 1);
		headers.add(new Header(CACHE_CONTROL, CACHE_CONTROL_NO_STORE));
		headers.addAll(additionalHeaders);
		return new MicrohttpResponse(status, reason, List.copyOf(headers), body);
	}

	private boolean authorizedHost(InetSocketAddress effectiveAddress,
			MicrohttpRequest request) {
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

		Set<String> allowedHosts = normalizedAllowedHosts(effectiveAddress);
		return allowedHosts.contains(hostAuthority.host());
	}

	private void validateConfiguredAllowedHosts() {
		for (String allowedHost : endpointPolicy.allowedHosts()) {
			Optional<HostAuthority> authority = parseConfiguredHost(allowedHost);
			if (!allowedHost.equals(trimOptionalWhitespace(allowedHost))
					|| authority.isEmpty() || authority.orElseThrow().port().isPresent())
				throw new IllegalArgumentException("Allowed hosts must contain only valid "
						+ "ASCII hostnames or IP literals without a port.");
		}
	}

	private Set<String> normalizedAllowedHosts(InetSocketAddress effectiveAddress) {
		Set<String> allowedHosts = new LinkedHashSet<>();

		InetAddress address = effectiveAddress.getAddress();
		if (address != null && address.isLoopbackAddress()) {
			addNormalizedHost(allowedHosts, effectiveAddress.getHostString());
			addNormalizedHost(allowedHosts, address.getHostAddress());
			addNormalizedHost(allowedHosts, transportConfiguration.host());
		}

		for (String allowedHost : endpointPolicy.allowedHosts())
			addNormalizedHost(allowedHosts, allowedHost);

		return Set.copyOf(allowedHosts);
	}

	private void addNormalizedHost(Set<String> hosts, String host) {
		parseConfiguredHost(host).filter(authority -> authority.port().isEmpty())
				.map(HostAuthority::host).ifPresent(hosts::add);
	}

	private Optional<HostAuthority> parseConfiguredHost(String value) {
		if (value == null)
			return Optional.empty();
		String authority = value.indexOf(':') >= 0 && !value.startsWith("[")
				? "[" + value + "]" : value;
		return parseHostAuthority(authority);
	}

	private Optional<HostAuthority> parseHostAuthority(String value) {
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

	private Optional<String> normalizeIpv6(String value) {
		try {
			InetAddress address = InetAddress.getByName(value);
			return address instanceof Inet6Address
					? Optional.of(address.getHostAddress().toLowerCase(Locale.ROOT))
					: Optional.empty();
		} catch (Exception exception) {
			return Optional.empty();
		}
	}

	private String normalizeRegName(String value) {
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

	private Optional<Integer> parsePort(String value) {
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

	private boolean validOrigin(String origin) {
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

	private Optional<Set<String>> requestedPreflightHeaders(MicrohttpRequest request) {
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

	private boolean containsOnlyIgnoreCase(Set<String> values, Set<String> allowedValues) {
		for (String value : values) {
			boolean allowed = allowedValues.stream()
					.anyMatch(allowedValue -> allowedValue.equalsIgnoreCase(value));
			if (!allowed)
				return false;
		}
		return true;
	}

	private Request toSokletRequest(MicrohttpRequest request, HttpMethod httpMethod) {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		for (Header header : request.headers()) {
			String matchingName = headers.keySet().stream()
					.filter(name -> name.equalsIgnoreCase(header.name()))
					.findFirst().orElse(header.name());
			Set<String> values = new LinkedHashSet<>(
					headers.getOrDefault(matchingName, Set.of()));
			values.add(header.value());
			headers.put(matchingName, Set.copyOf(values));
		}

		return Request.withRawUrl(httpMethod, request.uri())
				.headers(headers)
				.remoteAddress(request.remoteAddress())
				.body(request.body())
				.contentTooLarge(request.contentTooLarge())
				.build();
	}

	private String requestPath(String requestTarget) {
		try {
			URI uri = new URI(requestTarget);
			String path = uri.getRawPath();
			return path == null || path.isEmpty() ? "/" : path;
		} catch (URISyntaxException exception) {
			return "";
		}
	}

	private Optional<HttpMethod> httpMethod(String method) {
		try {
			return Optional.of(HttpMethod.valueOf(method));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private List<String> headerValues(MicrohttpRequest request, String name) {
		List<String> values = new ArrayList<>();
		for (Header header : request.headers()) {
			if (name.equalsIgnoreCase(header.name()))
				values.add(trimOptionalWhitespace(header.value()));
		}
		return List.copyOf(values);
	}

	private Optional<String> singleHeader(MicrohttpRequest request, String name) {
		List<String> values = headerValues(request, name);
		return values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
	}

	private String trimOptionalWhitespace(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t'))
			start++;
		while (end > start && (value.charAt(end - 1) == ' '
				|| value.charAt(end - 1) == '\t'))
			end--;
		return value.substring(start, end);
	}

	private List<String> splitSemicolonAware(String value) {
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

	private String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
			return value.substring(1, value.length() - 1);
		return value;
	}

	private boolean validParameterValue(String value) {
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

	private List<String> splitCommaAware(String value) {
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

	private boolean httpToken(String value) {
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

	private boolean ascii(String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < 0x21 || character > 0x7E)
				return false;
		}
		return true;
	}

	private void closeFailedStart(@Nullable EventLoop failedEventLoop,
			@Nullable ThreadPoolExecutor failedProcessor,
			@Nullable McpApplicationExecution failedApplicationExecution) {
		boolean interrupted = false;
		long cleanupStartedAt = System.nanoTime();
		long cleanupTimeoutNanos = transportConfiguration.shutdownTimeout().toNanos();

		if (failedEventLoop != null) {
			try {
				failedEventLoop.stop();
			} catch (Throwable ignored) {
				// Preserve the startup failure; residual state below blocks unsafe reuse.
			}
		}
		if (failedApplicationExecution != null) {
			try {
				failedApplicationExecution.stop();
			} catch (Throwable ignored) {
				// Preserve the startup failure; residual state below blocks unsafe reuse.
			}
		}
		if (failedProcessor != null) {
			try {
				failedProcessor.shutdownNow();
			} catch (Throwable ignored) {
				// Preserve the startup failure; residual state below blocks unsafe reuse.
			}
		}

		while (failedEventLoop != null && !failedEventLoop.isTerminated()) {
			long remainingNanos = remainingShutdownNanos(
					cleanupStartedAt, cleanupTimeoutNanos);
			if (remainingNanos <= 0L)
				break;
			try {
				if (failedEventLoop.join(Duration.ofNanos(remainingNanos)))
					break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}

		while (failedProcessor != null && !failedProcessor.isTerminated()) {
			long remainingNanos = remainingShutdownNanos(
					cleanupStartedAt, cleanupTimeoutNanos);
			if (remainingNanos <= 0L)
				break;
			try {
				failedProcessor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}

		while (failedApplicationExecution != null
				&& !failedApplicationExecution.isTerminated()) {
			long remainingNanos = remainingShutdownNanos(
					cleanupStartedAt, cleanupTimeoutNanos);
			if (remainingNanos <= 0L)
				break;
			try {
				if (failedApplicationExecution.awaitTermination(
						Duration.ofNanos(remainingNanos)))
					break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}

		if (failedEventLoop != null && !failedEventLoop.isTerminated())
			residualEventLoop = failedEventLoop;
		if (failedProcessor != null && !failedProcessor.isTerminated())
			residualRequestProcessor = failedProcessor;
		if (failedApplicationExecution != null
				&& !failedApplicationExecution.isTerminated())
			residualApplicationExecution = failedApplicationExecution;

		if (interrupted)
			Thread.currentThread().interrupt();
	}

	/**
	 * Arbitrates protocol-task ownership against asynchronous application
	 * ownership for one transport request. Handoff reserves application ownership
	 * at the generation boundary, then invokes registration while holding this
	 * control's lock so cancellation cannot fall into the gap between owners.
	 */
	private final class RequestControl {
		private final MicrohttpRequest request;
		private final long deadlineNanos;
		private final ThreadPoolExecutor processor;
		private final McpApplicationExecution application;
		private final Object lock;
		private @Nullable FutureTask<Void> protocolTask;
		private @Nullable Consumer<MicrohttpResponse> responseCallback;
		private @Nullable McpJsonRpcId registeredRequestId;
		private @Nullable McpRequestSseStream responseStream;
		private List<Header> deadlineResponseHeaders;
		private long nextKeepAliveNanos;
		private boolean applicationOwned;
		private boolean streamTerminalResponseOwned;
		private boolean streamAbortOwned;
		private boolean canceled;
		private boolean terminal;

		private RequestControl(MicrohttpRequest request, long deadlineNanos,
				ThreadPoolExecutor processor, McpApplicationExecution application,
				Consumer<MicrohttpResponse> responseCallback) {
			this.request = requireNonNull(request);
			this.deadlineNanos = deadlineNanos;
			this.processor = requireNonNull(processor);
			this.application = requireNonNull(application);
			this.lock = new Object();
			this.responseCallback = requireNonNull(responseCallback);
			this.deadlineResponseHeaders = List.of();
		}

		private long deadlineNanos() {
			return deadlineNanos;
		}

		private boolean protocolProcessingAllowed() {
			ProtocolProcessingReservation reservation;
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

			if (reservation == null)
				return false;
			if (reservation.deadlineExpiration() != null)
				finishProtocolDeadline(reservation.deadlineExpiration());
			return reservation.allowed();
		}

		private RequestIdRegistration registerRequestId(McpJsonRpcId requestId) {
			requireNonNull(requestId);
			synchronized (lock) {
				if (canceled || terminal)
					return RequestIdRegistration.TERMINATED;
				if (registeredRequestId != null)
					throw new IllegalStateException("The request ID is already registered.");
				if (activeRequestIds.putIfAbsent(requestId, this) != null)
					return RequestIdRegistration.DUPLICATE;
				registeredRequestId = requestId;
				return RequestIdRegistration.REGISTERED;
			}
		}

		private void submit(FutureTask<Void> task) {
			requireNonNull(task);
			ProtocolSubmission submission;
			synchronized (lock) {
				if (protocolTask != null)
					throw new IllegalStateException("The protocol task is already bound.");
				submission = application.reserveProtocolOperationIfRunning(() -> {
					protocolTask = task;
					requestControls.put(request, this);
					application.signalDeadlineTimer();
					try {
						processor.execute(task);
						return new ProtocolSubmission(null);
					} catch (RejectedExecutionException exception) {
						protocolTask = null;
						terminal = true;
						return new ProtocolSubmission(takeResponseCallback());
					} catch (RuntimeException | Error failure) {
						protocolTask = null;
						canceled = true;
						terminal = true;
						responseCallback = null;
						requestControls.remove(request, this);
						task.cancel(true);
						processor.remove(task);
						throw failure;
					}
				}).orElse(null);
				if (submission == null) {
					canceled = true;
					terminal = true;
					responseCallback = null;
				}
			}

			if (submission != null && submission.rejectedCallback() != null) {
				requestControls.remove(request, this);
				deliverResponse(submission.rejectedCallback(),
						emptyResponse(503, "Service Unavailable", List.of()));
			}
		}

		private boolean updateDeadlineResponseHeaders(List<Header> headers) {
			requireNonNull(headers);
			synchronized (lock) {
				if (canceled || terminal)
					return false;
				deadlineResponseHeaders = List.copyOf(headers);
				return true;
			}
		}

		private boolean handoff(McpApplicationExecution application,
				Runnable registration) {
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

					applicationOwned = true;
					return new ProtocolProcessingReservation(true, null);
				}).orElse(null);
				if (reservation != null && reservation.allowed()) {
					try {
						registration.run();
						if (applicationOwned)
							protocolTask = null;
					} catch (Throwable throwable) {
						if (!terminal)
							applicationOwned = false;
						throw throwable;
					}
				}
			}

			if (reservation == null)
				return false;
			if (reservation.deadlineExpiration() != null)
				finishProtocolDeadline(reservation.deadlineExpiration());
			return reservation.allowed();
		}

		private void completeProtocol(@Nullable MicrohttpResponse response) {
			ProtocolResponseReservation reservation;
			synchronized (lock) {
				if (applicationOwned || terminal)
					return;

				reservation = application.reserveProtocolOperationIfRunning(() -> {
					if (!canceled && response != null
							&& applicationClock.nanoTime() - deadlineNanos >= 0L)
						return new ProtocolResponseReservation(
								null, null, detachProtocolDeadline(false));

					protocolTask = null;
					terminal = true;
					Consumer<MicrohttpResponse> callback = !canceled && response != null
							? takeResponseCallback() : null;
					responseCallback = null;
					releaseRequestId();
					return new ProtocolResponseReservation(callback, response, null);
				}).orElse(null);
				if (reservation == null) {
					protocolTask = null;
					canceled = true;
					terminal = true;
					responseCallback = null;
					releaseRequestId();
				}
			}

			if (reservation == null) {
				requestControls.remove(request, this);
				return;
			}
			if (reservation.deadlineExpiration() != null) {
				finishProtocolDeadline(reservation.deadlineExpiration());
				return;
			}
			requestControls.remove(request, this);
			if (reservation.responseCallback() != null)
				deliverResponse(reservation.responseCallback(),
						requireNonNull(reservation.response()));
		}

		private boolean writeApplicationNotification(
				McpJsonRpcMessage.Notification notification,
				List<Header> additionalHeaders) throws InterruptedException {
			requireNonNull(notification);
			requireNonNull(additionalHeaders);
			McpRequestSseStream stream;
			Consumer<MicrohttpResponse> callback = null;
			boolean firstMessage = false;

			synchronized (lock) {
				if (!applicationOwned || streamAbortOwned || canceled || terminal)
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
					nextKeepAliveNanos = saturatingAdd(applicationClock.nanoTime(),
							transportConfiguration.keepAliveInterval().toNanos());
					callback = takeResponseCallback();
				}
			}

			if (firstMessage) {
				try {
					requireNonNull(callback).accept(stream.response(additionalHeaders));
				} catch (Throwable throwable) {
					stream.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
					return false;
				}
				return true;
			}

			stream.enqueueMessage(notification);
			return true;
		}

		private boolean writeApplicationResponse(McpApplicationResponse response,
				McpJsonRpcId requestId, List<Header> additionalHeaders) {
			requireNonNull(response);
			requireNonNull(requestId);
			requireNonNull(additionalHeaders);
			Consumer<MicrohttpResponse> callback = null;
			McpRequestSseStream stream;
			MicrohttpResponse jsonResponse = null;

			synchronized (lock) {
				if (!applicationOwned || streamAbortOwned || canceled || terminal)
					return false;

				applicationOwned = false;
				protocolTask = null;
				stream = responseStream;
				if (stream == null) {
					terminal = true;
					callback = takeResponseCallback();
					releaseRequestId();
				} else
					streamTerminalResponseOwned = true;
			}

			if (stream == null) {
				jsonResponse = applicationResponse(
						response, requestId, additionalHeaders);
				requestControls.remove(request, this);
				deliverResponse(requireNonNull(callback), requireNonNull(jsonResponse));
				return true;
			}

			if (response.message().isEmpty())
				return stream.fail(StreamTerminationReason.RESPONSE_TIMEOUT, null);

			try {
				return stream.completeMessage(response.message().orElseThrow());
			} catch (Throwable throwable) {
				return stream.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}

		private McpRequestSseStream newResponseStream() {
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
						public void didTerminate(StreamTerminationReason reason,
								@Nullable Throwable cause) {
							streamTerminated(reason, cause);
						}
					});
		}

		private Optional<McpOutboundChannel.Snapshot> streamSnapshot() {
			synchronized (lock) {
				return responseStream == null ? Optional.empty()
						: Optional.of(responseStream.snapshot());
			}
		}

		private void cancel(StreamTerminationReason reason, @Nullable Throwable cause) {
			FutureTask<Void> task;
			McpRequestSseStream stream;
			boolean remove;
			synchronized (lock) {
				if (terminal)
					return;

				canceled = true;
				task = protocolTask;
				protocolTask = null;
				stream = responseStream;
				remove = !applicationOwned;
				if (applicationOwned) {
					// Application cancellation runs under the ownership lock. Its
					// terminal cleanup is reentrant, and this closes the gap in which a
					// queued deadline or handler response could otherwise win after the
					// transport had already marked this control canceled.
					application.cancel(request, requireNonNull(reason), cause);
					if (!terminal) {
						applicationOwned = false;
						terminal = true;
						responseCallback = null;
						releaseRequestId();
						remove = true;
					}
				} else {
					terminal = true;
					responseCallback = null;
					releaseRequestId();
				}
			}

			if (task != null) {
				task.cancel(true);
				processor.remove(task);
			}
			if (stream != null)
				stream.close(reason, cause);
			if (remove)
				requestControls.remove(request, this);
		}

		private void onTimer(long nowNanos) {
			McpRequestSseStream stream;
			synchronized (lock) {
				if (terminal || canceled)
					return;
				stream = responseStream;
			}

			if (stream != null) {
				if (stream.failIfDeadlineExpired(nowNanos, deadlineNanos,
						StreamTerminationReason.RESPONSE_TIMEOUT, null)) {
					application.recordStreamDeadlineExpiration();
					return;
				}
				if (stream.failIfWriteIdleExpired(nowNanos,
						transportConfiguration.responseWriteIdleTimeout().toNanos(),
						StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null))
					return;

				boolean terminateForBackpressure = false;
				synchronized (lock) {
					if (terminal || canceled || responseStream != stream
							|| streamTerminalResponseOwned || streamAbortOwned
							|| nowNanos - deadlineNanos >= 0L)
						return;
					if (nowNanos - nextKeepAliveNanos >= 0L) {
						McpOutboundChannel.OfferResult result = stream.offerKeepAlive();
						if (result == McpOutboundChannel.OfferResult.ACCEPTED) {
							nextKeepAliveNanos = saturatingAdd(nowNanos,
									transportConfiguration.keepAliveInterval().toNanos());
						} else if (result == McpOutboundChannel.OfferResult.FULL
								|| result == McpOutboundChannel.OfferResult.TOO_LARGE) {
							streamAbortOwned = true;
							terminateForBackpressure = true;
						}
					}
				}
				if (terminateForBackpressure)
					stream.fail(StreamTerminationReason.BACKPRESSURE, null);
				return;
			}

			if (nowNanos - deadlineNanos < 0L)
				return;

			ProtocolDeadlineExpiration expiration;
			synchronized (lock) {
				if (terminal || canceled || applicationOwned)
					return;

				expiration = application.reserveProtocolOperationIfRunning(
						() -> detachProtocolDeadline(true)).orElse(null);
				if (expiration == null)
					return;
			}

			finishProtocolDeadline(expiration);
		}

		private void streamTerminated(StreamTerminationReason reason,
				@Nullable Throwable cause) {
			requireNonNull(reason);
			boolean cancelApplication;
			synchronized (lock) {
				if (terminal)
					return;

				cancelApplication = applicationOwned
						&& reason != StreamTerminationReason.COMPLETED;
				if (reason != StreamTerminationReason.COMPLETED)
					canceled = true;
				applicationOwned = false;
				terminal = true;
				protocolTask = null;
				responseCallback = null;
				releaseRequestId();
			}

			requestControls.remove(request, this);
			if (cancelApplication)
				application.cancel(request, reason, cause);
		}

		private ProtocolDeadlineExpiration detachProtocolDeadline(boolean cancelTask) {
			canceled = true;
			terminal = true;
			FutureTask<Void> task = protocolTask;
			protocolTask = null;
			Consumer<MicrohttpResponse> callback = takeResponseCallback();
			releaseRequestId();
			return new ProtocolDeadlineExpiration(
					cancelTask ? task : null, callback, deadlineResponseHeaders);
		}

		private void finishProtocolDeadline(ProtocolDeadlineExpiration expiration) {
			requireNonNull(expiration);
			requestControls.remove(request, this);
			if (expiration.task() != null) {
				expiration.task().cancel(true);
				processor.remove(expiration.task());
			}
			application.recordProtocolDeadlineExpiration();
			deliverResponse(expiration.responseCallback(),
					emptyResponse(504, "Gateway Timeout", expiration.responseHeaders()));
		}

		private void applicationTerminated() {
			boolean remove;
			synchronized (lock) {
				applicationOwned = false;
				protocolTask = null;
				remove = responseStream == null || canceled || terminal;
				if (remove) {
					terminal = true;
					responseCallback = null;
					releaseRequestId();
				}
			}
			if (remove)
				requestControls.remove(request, this);
		}

		private Consumer<MicrohttpResponse> takeResponseCallback() {
			Consumer<MicrohttpResponse> callback = requireNonNull(responseCallback,
					"An open request must retain its response callback.");
			responseCallback = null;
			return callback;
		}

		private void deliverResponse(Consumer<MicrohttpResponse> callback,
				MicrohttpResponse response) {
			try {
				callback.accept(response);
			} catch (Throwable ignored) {
				// A reserved terminal outcome remains authoritative on delivery failure.
			}
		}

		private void releaseRequestId() {
			McpJsonRpcId requestId = registeredRequestId;
			registeredRequestId = null;
			if (requestId != null)
				activeRequestIds.remove(requestId, this);
		}
	}

	private enum RequestIdRegistration {
		REGISTERED,
		DUPLICATE,
		TERMINATED
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

	private record ProtocolDeadlineExpiration(@Nullable FutureTask<Void> task,
			Consumer<MicrohttpResponse> responseCallback,
			List<Header> responseHeaders) {
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
			@Nullable Consumer<MicrohttpResponse> responseCallback,
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
			@Nullable Consumer<MicrohttpResponse> rejectedCallback) {
	}

	private record HostAuthority(String host, Optional<Integer> port) {
		private HostAuthority {
			requireNonNull(host);
			requireNonNull(port);
		}
	}

	private record CorsAuthorization(Optional<CorsResponse> response,
			Optional<MicrohttpResponse> rejection) {
		private CorsAuthorization {
			requireNonNull(response);
			requireNonNull(rejection);
			if (response.isPresent() && rejection.isPresent())
				throw new IllegalArgumentException(
						"CORS authorization cannot both accept and reject.");
		}

		private static CorsAuthorization withoutOrigin() {
			return new CorsAuthorization(Optional.empty(), Optional.empty());
		}

		private static CorsAuthorization accepted(CorsResponse response) {
			return new CorsAuthorization(Optional.of(response), Optional.empty());
		}

		private static CorsAuthorization rejected(MicrohttpResponse response) {
			return new CorsAuthorization(Optional.empty(), Optional.of(response));
		}
	}
}
