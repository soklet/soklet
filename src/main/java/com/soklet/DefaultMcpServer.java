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

import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.util.HostHeaderValidator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.soklet.Utilities.emptyByteArray;
import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpServer implements McpServer, InternalMcpSessionMessagePublisher {
	@NonNull
	private static final String DEFAULT_HOST;
	@NonNull
	private static final Duration ACCEPT_FAILURE_BACKOFF = Duration.ofMillis(50);
	@NonNull
	private static final Duration ACCEPT_FAILURE_BACKOFF_MAX = Duration.ofSeconds(1);
	@NonNull
	private static final Duration DEFAULT_REQUEST_HEADER_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_BODY_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HANDLER_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_HEADER_COUNT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_CONCURRENT_CONNECTION_LIMIT;
	@NonNull
	private static final Integer DEFAULT_CONNECTION_QUEUE_CAPACITY;
	@NonNull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_WRITE_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_HEARTBEAT_INTERVAL;
	@NonNull
	private static final byte[] HEARTBEAT_BYTES;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_HEADER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_BODY_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER = 64;
		DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER = 16;
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_MAXIMUM_HEADER_COUNT = 100;
		DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES = 64 * 1_024;
		DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES = 8_192;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_CONCURRENT_CONNECTION_LIMIT = 8_192;
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 128;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
		DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);
		DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
		HEARTBEAT_BYTES = ":\n\n".getBytes(StandardCharsets.UTF_8);
	}

	@NonNull
	private final Integer port;
	@NonNull
	private final String host;
	@NonNull
	private final McpHandlerResolver handlerResolver;
	@NonNull
	private final McpRequestAdmissionPolicy requestAdmissionPolicy;
	@NonNull
	private final McpRequestInterceptor requestInterceptor;
	@NonNull
	private final McpResponseMarshaler responseMarshaler;
	@NonNull
	private final McpCorsAuthorizer corsAuthorizer;
	@NonNull
	private final McpSessionStore sessionStore;
	@NonNull
	private final Duration requestHeaderTimeout;
	@NonNull
	private final Duration requestBodyTimeout;
	@NonNull
	private final Duration requestHandlerTimeout;
	@NonNull
	private final Integer requestHandlerConcurrency;
	@NonNull
	private final Integer requestHandlerQueueCapacity;
	@NonNull
	private final Integer maximumRequestSizeInBytes;
	@NonNull
	private final Integer maximumHeaderCount;
	@NonNull
	private final Integer maximumHeadersSizeInBytes;
	@NonNull
	private final Integer maximumRequestTargetLengthInBytes;
	@NonNull
	private final Integer requestReadBufferSizeInBytes;
	@NonNull
	private final Integer concurrentConnectionLimit;
	@NonNull
	private final Integer connectionQueueCapacity;
	@NonNull
	private final Duration shutdownTimeout;
	@NonNull
	private final Duration writeTimeout;
	@NonNull
	private final Duration heartbeatInterval;
	@NonNull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@NonNull
	private final Supplier<ExecutorService> connectionExecutorServiceSupplier;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final AtomicBoolean stopPoisonPill;
	@NonNull
	private final ConcurrentHashMap<@NonNull String, @NonNull CopyOnWriteArrayList<@NonNull McpLiveConnection>> liveConnectionsBySessionId;
	@NonNull
	private final AtomicInteger activeConnectionCount;
	@Nullable
	private volatile SokletConfig sokletConfig;
	@Nullable
	private volatile RequestHandler requestHandler;
	@Nullable
	private volatile DefaultMcpRuntime mcpRuntime;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile TimeoutScheduler requestHandlerTimeoutScheduler;
	@Nullable
	private volatile ExecutorService connectionExecutorService;
	@Nullable
	private volatile Thread acceptThread;
	@Nullable
	private volatile ServerSocket serverSocket;
	@NonNull
	private volatile Boolean started;
	@NonNull
	private volatile Boolean stopping;
	private long lifecycleGeneration;
	// Tracks back-to-back accept-loop failures so the loop escalates backoff and coalesces logging
	// instead of storming. Touched on the mcp-accept-loop thread; AtomicLong for safe publication on restart.
	private final AtomicLong consecutiveAcceptFailures = new AtomicLong();

	DefaultMcpServer(McpServer.Builder builder) {
		requireNonNull(builder);

		if (builder.handlerResolver == null)
			throw new IllegalStateException("You must specify an McpHandlerResolver when building an McpServer.");

		this.handlerResolver = builder.handlerResolver;
		this.requestAdmissionPolicy = builder.requestAdmissionPolicy != null ? builder.requestAdmissionPolicy : McpRequestAdmissionPolicy.defaultInstance();
		this.requestInterceptor = builder.requestInterceptor != null ? builder.requestInterceptor : new McpRequestInterceptor() {};
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : McpResponseMarshaler.defaultInstance();
		this.corsAuthorizer = builder.corsAuthorizer != null ? builder.corsAuthorizer : McpCorsAuthorizer.nonBrowserClientsOnlyInstance();
		this.sessionStore = builder.sessionStore != null ? builder.sessionStore : McpSessionStore.fromInMemory();
		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.requestHeaderTimeout = builder.requestHeaderTimeout != null ? builder.requestHeaderTimeout : DEFAULT_REQUEST_HEADER_TIMEOUT;
		this.requestBodyTimeout = builder.requestBodyTimeout != null ? builder.requestBodyTimeout : DEFAULT_REQUEST_BODY_TIMEOUT;
		this.requestHandlerTimeout = builder.requestHandlerTimeout != null ? builder.requestHandlerTimeout : DEFAULT_REQUEST_HANDLER_TIMEOUT;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.maximumHeaderCount = builder.maximumHeaderCount != null ? builder.maximumHeaderCount : DEFAULT_MAXIMUM_HEADER_COUNT;
		this.maximumHeadersSizeInBytes = builder.maximumHeadersSizeInBytes != null ? builder.maximumHeadersSizeInBytes : DEFAULT_MAXIMUM_HEADERS_SIZE_IN_BYTES;
		this.maximumRequestTargetLengthInBytes = builder.maximumRequestTargetLengthInBytes != null ? builder.maximumRequestTargetLengthInBytes : DEFAULT_MAXIMUM_REQUEST_TARGET_LENGTH_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : DEFAULT_CONCURRENT_CONNECTION_LIMIT;
		this.connectionQueueCapacity = builder.connectionQueueCapacity != null ? builder.connectionQueueCapacity : DEFAULT_CONNECTION_QUEUE_CAPACITY;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.writeTimeout = builder.writeTimeout != null ? builder.writeTimeout : DEFAULT_WRITE_TIMEOUT;
		this.heartbeatInterval = builder.heartbeatInterval != null ? builder.heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
		this.lock = new ReentrantLock();
		this.stopPoisonPill = new AtomicBoolean(false);
		this.liveConnectionsBySessionId = new ConcurrentHashMap<>();
		this.activeConnectionCount = new AtomicInteger(0);
		this.started = false;
		this.stopping = false;

		int defaultRequestHandlerConcurrency = Utilities.virtualThreadsAvailable()
				? Math.max(1, Runtime.getRuntime().availableProcessors() * DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER)
				: Math.max(1, Runtime.getRuntime().availableProcessors());

		this.requestHandlerConcurrency = builder.requestHandlerConcurrency != null
				? builder.requestHandlerConcurrency
				: defaultRequestHandlerConcurrency;

		if (this.requestHandlerConcurrency < 1)
			throw new IllegalArgumentException("Request handler concurrency must be > 0");

		this.requestHandlerQueueCapacity = builder.requestHandlerQueueCapacity != null
				? builder.requestHandlerQueueCapacity
				: Math.max(1, this.requestHandlerConcurrency * DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER);

		if (this.requestHandlerQueueCapacity < 1)
			throw new IllegalArgumentException("Request handler queue capacity must be > 0");

		if (this.maximumRequestSizeInBytes < 1)
			throw new IllegalArgumentException("Maximum request size must be > 0");

		if (this.maximumHeaderCount < 1)
			throw new IllegalArgumentException("Maximum header count must be > 0");

		if (this.maximumHeadersSizeInBytes < 1)
			throw new IllegalArgumentException("Maximum headers size must be > 0");

		if (this.maximumRequestTargetLengthInBytes < 1)
			throw new IllegalArgumentException("Maximum request target length must be > 0");

		if (this.requestReadBufferSizeInBytes < 1)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.connectionQueueCapacity < 1)
			throw new IllegalArgumentException("Connection queue capacity must be > 0");

		if (this.concurrentConnectionLimit < 0)
			throw new IllegalArgumentException("Concurrent connection limit must be >= 0");

		if (this.requestHeaderTimeout.isNegative() || this.requestHeaderTimeout.isZero())
			throw new IllegalArgumentException("Request header timeout must be > 0");

		if (this.requestBodyTimeout.isNegative() || this.requestBodyTimeout.isZero())
			throw new IllegalArgumentException("Request body timeout must be > 0");

		if (this.requestHandlerTimeout.isNegative() || this.requestHandlerTimeout.isZero())
			throw new IllegalArgumentException("Request handler timeout must be > 0");

		if (this.shutdownTimeout.isNegative() || this.shutdownTimeout.isZero())
			throw new IllegalArgumentException("Shutdown timeout must be > 0");

		if (this.heartbeatInterval.isNegative() || this.heartbeatInterval.isZero())
			throw new IllegalArgumentException("Heartbeat interval must be > 0");

		if (this.writeTimeout.isNegative())
			throw new IllegalArgumentException("Write timeout must be >= 0");

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null
				? builder.requestHandlerExecutorServiceSupplier
				: () -> createExecutorService("mcp-request-handler-", this.requestHandlerConcurrency, this.requestHandlerQueueCapacity);
		this.connectionExecutorServiceSupplier = this::createConnectionExecutorService;
	}

	void mcpRuntime(@NonNull DefaultMcpRuntime mcpRuntime) {
		requireNonNull(mcpRuntime);
		this.mcpRuntime = mcpRuntime;
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (this.started)
				return;

			if (this.requestHandler == null)
				throw new IllegalStateException(format("No %s was registered for %s", RequestHandler.class.getSimpleName(), getClass().getSimpleName()));

			ServerSocket serverSocket = new ServerSocket();
			serverSocket.setReuseAddress(true);

			try {
				serverSocket.bind(new InetSocketAddress(this.host, this.port));
			} catch (BindException e) {
				throw new IllegalStateException(format("Soklet was unable to start the MCP server - port %d is already in use.", this.port), e);
			}

			this.serverSocket = serverSocket;
			this.requestHandlerExecutorService = this.requestHandlerExecutorServiceSupplier.get();
			this.requestHandlerTimeoutScheduler = new TimeoutScheduler(new DefaultHttpServer.NonvirtualThreadFactory("mcp-request-timeout"));
			this.connectionExecutorService = this.connectionExecutorServiceSupplier.get();
			this.stopPoisonPill.set(false);
			this.stopping = false;
			this.liveConnectionsBySessionId.clear();
			this.activeConnectionCount.set(0);
			this.consecutiveAcceptFailures.set(0);
			this.lifecycleGeneration++;
			long lifecycleGeneration = this.lifecycleGeneration;
			this.started = true;

			Thread acceptThread = new Thread(() -> acceptLoop(lifecycleGeneration), "mcp-accept-loop");
			this.acceptThread = acceptThread;
			acceptThread.start();
		} catch (RuntimeException e) {
			cleanupAfterFailedStart();
			throw e;
		} catch (IOException e) {
			cleanupAfterFailedStart();
			throw new IllegalStateException("Unable to start MCP server", e);
		} finally {
			getLock().unlock();
		}
	}

	@Override
	public void stop() {
		Thread acceptThreadSnapshot;
		ServerSocket serverSocketSnapshot;
		ExecutorService requestHandlerExecutorServiceSnapshot;
		TimeoutScheduler requestHandlerTimeoutSchedulerSnapshot;
		ExecutorService connectionExecutorServiceSnapshot;
		long lifecycleGenerationSnapshot;

		getLock().lock();

		try {
			if (!this.started)
				return;

			this.stopping = true;
			this.stopPoisonPill.set(true);
			lifecycleGenerationSnapshot = this.lifecycleGeneration;
			acceptThreadSnapshot = this.acceptThread;
			serverSocketSnapshot = this.serverSocket;
			requestHandlerExecutorServiceSnapshot = this.requestHandlerExecutorService;
			requestHandlerTimeoutSchedulerSnapshot = this.requestHandlerTimeoutScheduler;
			connectionExecutorServiceSnapshot = this.connectionExecutorService;
		} finally {
			getLock().unlock();
		}

		if (serverSocketSnapshot != null) {
			try {
				serverSocketSnapshot.close();
			} catch (IOException ignored) {
				// Nothing to do
			}
		}

		for (CopyOnWriteArrayList<McpLiveConnection> connections : new ArrayList<>(this.liveConnectionsBySessionId.values())) {
			for (McpLiveConnection connection : connections)
				closeLiveConnection(connection, StreamTerminationReason.SERVER_STOPPING, null, true);
		}

		this.liveConnectionsBySessionId.clear();

		if (requestHandlerExecutorServiceSnapshot != null)
			requestHandlerExecutorServiceSnapshot.shutdown();

		if (requestHandlerTimeoutSchedulerSnapshot != null)
			requestHandlerTimeoutSchedulerSnapshot.shutdown();

		if (connectionExecutorServiceSnapshot != null)
			connectionExecutorServiceSnapshot.shutdown();

		final long deadlineNanos = System.nanoTime() + this.shutdownTimeout.toNanos();

		try {
			long grace = remainingMillis(deadlineNanos);
			List<CompletableFuture<Boolean>> waits = new ArrayList<>(4);
			waits.add(joinAsync(acceptThreadSnapshot, grace));

			if (requestHandlerExecutorServiceSnapshot != null)
				waits.add(awaitTerminationAsync(requestHandlerExecutorServiceSnapshot, grace));

			if (requestHandlerTimeoutSchedulerSnapshot != null)
				waits.add(awaitTerminationAsync(requestHandlerTimeoutSchedulerSnapshot, grace));

			if (connectionExecutorServiceSnapshot != null)
				waits.add(awaitTerminationAsync(connectionExecutorServiceSnapshot, grace));

			try {
				CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).get(grace, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
				// Budget exhausted; escalate below.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "Exception while awaiting MCP shutdown").throwable(e).build());
			}

			hardenJoin(acceptThreadSnapshot, remainingMillis(deadlineNanos));

			shutdownNowIfNeeded(requestHandlerExecutorServiceSnapshot);

			if (requestHandlerTimeoutSchedulerSnapshot != null)
				requestHandlerTimeoutSchedulerSnapshot.shutdownNow();

			shutdownNowIfNeeded(connectionExecutorServiceSnapshot);

			awaitPoolTermination(requestHandlerExecutorServiceSnapshot, remainingMillis(deadlineNanos));
			awaitSchedulerTermination(requestHandlerTimeoutSchedulerSnapshot, remainingMillis(deadlineNanos));
			awaitPoolTermination(connectionExecutorServiceSnapshot, remainingMillis(deadlineNanos));
		} finally {
			getLock().lock();

			try {
				if (this.lifecycleGeneration == lifecycleGenerationSnapshot) {
					this.acceptThread = null;
					this.serverSocket = null;
					this.requestHandlerExecutorService = null;
					this.requestHandlerTimeoutScheduler = null;
					this.connectionExecutorService = null;
					this.started = false;
					this.stopping = false;
				}
			} finally {
				getLock().unlock();
			}
		}
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		return this.started;
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		requireNonNull(sokletConfig);
		requireNonNull(requestHandler);
		this.sokletConfig = sokletConfig;
		this.requestHandler = requestHandler;
	}

	@NonNull
	@Override
	public McpHandlerResolver getHandlerResolver() {
		return this.handlerResolver;
	}

	@NonNull
	@Override
	public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
		return this.requestAdmissionPolicy;
	}

	@NonNull
	@Override
	public McpRequestInterceptor getRequestInterceptor() {
		return this.requestInterceptor;
	}

	@NonNull
	@Override
	public McpResponseMarshaler getResponseMarshaler() {
		return this.responseMarshaler;
	}

	@NonNull
	@Override
	public McpCorsAuthorizer getCorsAuthorizer() {
		return this.corsAuthorizer;
	}

	@NonNull
	@Override
	public McpSessionStore getSessionStore() {
		return this.sessionStore;
	}

	void terminateStreamsForSession(@NonNull String sessionId) {
		requireNonNull(sessionId);

		CopyOnWriteArrayList<McpLiveConnection> connections = this.liveConnectionsBySessionId.remove(sessionId);

		if (connections == null)
			return;

		for (McpLiveConnection connection : connections)
			closeLiveConnection(connection, StreamTerminationReason.SESSION_TERMINATED, null, false);
	}

	private void acceptLoop(long lifecycleGeneration) {
		ServerSocket serverSocket = this.serverSocket;

		if (serverSocket == null)
			return;

		while (!this.stopPoisonPill.get()) {
			Socket socket = null;
			InetSocketAddress remoteAddress = null;

			try {
				socket = serverSocket.accept();
				remoteAddress = remoteAddress(socket);
				notifyWillAcceptConnection(remoteAddress);
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(Math.toIntExact(Math.max(1L, this.requestHeaderTimeout.toMillis())));
				notifyDidAcceptConnection(remoteAddress);
				submitRequest(socket, remoteAddress);

				// A full iteration succeeded, so the accept path is healthy again
				noteAcceptRecovery();
			} catch (SocketException e) {
				if (this.stopPoisonPill.get())
					break;

				handleAcceptLoopFailure(e, socket, remoteAddress,
						"MCP accept loop encountered an IO error",
						MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR, "accept_loop_error");
			} catch (IOException e) {
				if (this.stopPoisonPill.get())
					break;

				handleAcceptLoopFailure(e, socket, remoteAddress,
						"MCP accept loop encountered an IO error",
						MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR, "accept_loop_error");
			} catch (RuntimeException e) {
				if (this.stopPoisonPill.get())
					break;

				handleAcceptLoopFailure(e, socket, remoteAddress,
						"MCP accept loop encountered an unexpected error",
						MetricsCollector.TransportFailureReason.CONNECTION_SETUP_ERROR, "connection_setup_error");
			} catch (Throwable t) {
				if (this.stopPoisonPill.get() || this.stopping)
					break;

				closeQuietly(socket);
				cleanupAfterUnexpectedAcceptLoopTermination(t, lifecycleGeneration);
				break;
			}
		}
	}

	private void handleAcceptLoopFailure(@NonNull Throwable throwable,
																			 @Nullable Socket socket,
																			 @Nullable InetSocketAddress remoteAddress,
																			 @NonNull String logMessage,
																			 MetricsCollector.@NonNull TransportFailureReason reason,
																			 @NonNull String detail) {
		long failures = this.consecutiveAcceptFailures.incrementAndGet();
		notifyDidFailToAcceptConnection(remoteAddress, ConnectionRejectionReason.INTERNAL_ERROR, throwable);
		closeQuietly(socket);

		// Coalesce log volume during a sustained failure (e.g. file-descriptor exhaustion):
		// log the first failure and then only at exponentially-spaced milestones.
		if (isPowerOfTwo(failures))
			safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, logMessage)
					.throwable(throwable)
					.build());

		recordTransportFailure(reason, throwable, detail);
		backoffAfterAcceptFailure(failures);
	}

	// Escalating backoff: a persistent accept-loop failure (e.g. EMFILE) would otherwise spin the
	// accept loop with no delay at all. Double the delay per consecutive failure up to a 1s ceiling.
	private void backoffAfterAcceptFailure(long consecutiveFailures) {
		long base = ACCEPT_FAILURE_BACKOFF.toMillis();
		long max = ACCEPT_FAILURE_BACKOFF_MAX.toMillis();
		int shift = (int) Math.min(Math.max(0L, consecutiveFailures - 1L), 20L);

		try {
			Thread.sleep(Math.min(max, base << shift));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.stopPoisonPill.set(true);
		}
	}

	private void noteAcceptRecovery() {
		this.consecutiveAcceptFailures.set(0);
	}

	private static boolean isPowerOfTwo(long value) {
		return value > 0 && (value & (value - 1)) == 0;
	}

	private void submitRequest(@NonNull Socket socket,
														 @Nullable InetSocketAddress remoteAddress) {
		requireNonNull(socket);

		ExecutorService requestHandlerExecutorService = this.requestHandlerExecutorService;

		if (requestHandlerExecutorService == null || requestHandlerExecutorService.isShutdown()) {
			IllegalStateException exception = new IllegalStateException("MCP request handler executor service is unavailable");
			notifyDidFailToAcceptRequest(remoteAddress, null, RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN, exception);
			closeQuietly(socket);
			return;
		}

		try {
			requestHandlerExecutorService.submit(() -> handleSocket(socket));
		} catch (RejectedExecutionException e) {
			notifyDidFailToAcceptRequest(remoteAddress, null, rejectionReasonFor(requestHandlerExecutorService), e);
			writePlainTextResponse(socket, 503, "Service unavailable");
			closeQuietly(socket);
		}
	}

	private void handleSocket(@NonNull Socket socket) {
		requireNonNull(socket);

		Request request = null;
		boolean handedOffToStreamProcessor = false;
		AtomicBoolean connectionSlotReserved = new AtomicBoolean(false);
		InetSocketAddress remoteAddress = remoteAddress(socket);

		try {
			notifyWillReadRequest(remoteAddress, null);
			request = readRequest(socket);
			notifyDidReadRequest(remoteAddress, request.getRawPathAndQuery());
			notifyWillAcceptRequest(remoteAddress, request.getRawPathAndQuery());
			notifyDidAcceptRequest(remoteAddress, request.getRawPathAndQuery());
			HttpRequestResult requestResult = invokeRequestHandler(request, socket);

			if (requestResult == null) {
				writePlainTextResponse(socket, request, 500, "Internal server error");
				return;
			}

			if (isLiveEventStreamResponse(request, requestResult)) {
				McpLiveConnection liveConnection = null;

				try {
					if (!reserveConnectionSlot(connectionSlotReserved)) {
						terminateRuntimeStreamIfPresent(request, StreamTerminationReason.SESSION_TERMINATED, null);
						writeMarshaledResponse(socket, request, serviceUnavailableResponse(request), true);
						return;
					}

					liveConnection = registerLiveConnection(socket, request, connectionSlotReserved);

					if (!sessionAllowsLiveGetStream(liveConnection.sessionId())) {
						liveConnection.terminationReason().compareAndSet(null, StreamTerminationReason.SESSION_TERMINATED);
						finishConnection(liveConnection, null);
						return;
					}

					writeAcceptedEventStreamResponse(socket, request, requestResult.getMarshaledResponse());
					markConnectionEstablished(liveConnection);
					startConnectionProcessor(liveConnection);
					handedOffToStreamProcessor = true;
					return;
				} catch (Throwable throwable) {
					if (liveConnection != null) {
						releaseReservedConnectionSlot(liveConnection.slotReserved());
						removeConnection(liveConnection);
					}

					terminateRuntimeStreamIfPresent(request,
							isRemoteClose(throwable) ? StreamTerminationReason.CLIENT_DISCONNECTED : StreamTerminationReason.WRITE_FAILED,
							throwable);

					if (throwable instanceof IOException ioException)
						throw ioException;

					throw throwable;
				}
			}

			writeMarshaledResponse(socket, request, requestResult.getMarshaledResponse(), true);

			if (request.getHttpMethod() == HttpMethod.DELETE
					&& Integer.valueOf(204).equals(requestResult.getMarshaledResponse().getStatusCode()))
				request.getHeader("MCP-Session-Id").ifPresent(this::terminateStreamsForSession);
		} catch (RequestTooLargeException e) {
			notifyDidFailToReadRequest(remoteAddress, null, RequestReadFailureReason.REQUEST_READ_REJECTED, e);
			String event = switch (e.reason()) {
				case HEADERS -> "exceed_request_headers_max_close";
				case URI_TOO_LONG -> "exceed_request_target_max_close";
				case CONTENT -> "exceed_request_max_close";
			};
			recordTransportFailure(MetricsCollector.TransportFailureReason.REQUEST_TOO_LARGE, e, event);
			if (e.reason() == RequestTooLargeException.Reason.HEADERS)
				writePlainTextResponse(socket, 431, "Request header fields too large");
			else if (e.reason() == RequestTooLargeException.Reason.URI_TOO_LONG)
				writePlainTextResponse(socket, 414, "URI too long");
			else
				writePlainTextResponse(socket, 413, "Request entity too large");
		} catch (SocketTimeoutException e) {
			if (request == null && !requestReadTimeoutMadeProgress(e))
				return;

			if (request == null) {
				notifyDidFailToReadRequest(remoteAddress, null, RequestReadFailureReason.REQUEST_READ_TIMEOUT, e);
				recordTransportFailure(MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT, e, "request_timeout");
			}
			writePlainTextResponse(socket, 408, "Request timed out");
		} catch (IllegalRequestException e) {
			if (request == null)
				notifyDidFailToReadRequest(remoteAddress, null, RequestReadFailureReason.UNPARSEABLE_REQUEST, e);
			recordTransportFailure(MetricsCollector.TransportFailureReason.MALFORMED_REQUEST, e, "malformed_request");
			writePlainTextResponse(socket, 400, "Bad request");
		} catch (IOException e) {
			if (request == null)
				recordTransportFailure(MetricsCollector.TransportFailureReason.READ_ERROR, e, "read_error");
		} catch (Throwable throwable) {
			if (request == null)
				notifyDidFailToReadRequest(remoteAddress, null, RequestReadFailureReason.INTERNAL_ERROR, throwable);
			if (request == null)
				recordTransportFailure(MetricsCollector.TransportFailureReason.TASK_ERROR, throwable, "task_error");

			safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An unexpected error occurred during MCP request handling")
					.throwable(throwable)
					.request(request)
					.build());
			if (request == null)
				writePlainTextResponse(socket, 500, "Internal server error");
			else
				writePlainTextResponse(socket, request, 500, "Internal server error");
		} finally {
			if (!handedOffToStreamProcessor)
				releaseReservedConnectionSlot(connectionSlotReserved);

			if (!handedOffToStreamProcessor)
				closeQuietly(socket);
		}
	}

	@NonNull
	private MarshaledResponse serviceUnavailableResponse(@NonNull Request request) {
		requireNonNull(request);

		SokletConfig sokletConfig = this.sokletConfig;

		if (sokletConfig == null) {
			return MarshaledResponse.withStatusCode(503)
					.headers(Map.of(
							"Content-Type", Set.of("text/plain; charset=UTF-8"),
							"Connection", Set.of("close")
					))
					.body("HTTP 503: Service Unavailable".getBytes(StandardCharsets.UTF_8))
					.build();
		}

		return sokletConfig.getResponseMarshaler().forServiceUnavailable(request, null);
	}

	@Nullable
	private HttpRequestResult invokeRequestHandler(@NonNull Request request,
																						 @NonNull Socket socket) throws Exception {
		requireNonNull(request);
		requireNonNull(socket);

		RequestHandler requestHandler = this.requestHandler;

		if (requestHandler == null)
			throw new IllegalStateException("MCP request handler is unavailable");

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<HttpRequestResult> requestResultReference = new AtomicReference<>();
		AtomicReference<Throwable> callbackThrowableReference = new AtomicReference<>();
		AtomicReference<Thread> handlerThreadReference = new AtomicReference<>(Thread.currentThread());
		AtomicBoolean timedOut = new AtomicBoolean(false);
		TimeoutScheduler.ScheduledTask timeoutTask = scheduleTimeout(socket, handlerThreadReference.get(), timedOut);

		try {
			requestHandler.handleRequest(request, requestResult -> {
				try {
					requestResultReference.set(requestResult);
				} catch (Throwable throwable) {
					callbackThrowableReference.set(throwable);
				} finally {
					latch.countDown();
				}
			});

			if (!latch.await(Math.max(1L, this.requestHandlerTimeout.toMillis()), TimeUnit.MILLISECONDS)) {
				timedOut.set(true);
				throw new SocketTimeoutException("MCP request handling timed out");
			}
		} catch (InterruptedException e) {
			if (timedOut.get())
				throw new SocketTimeoutException("MCP request handling timed out");

			Thread.currentThread().interrupt();
			throw e;
		} finally {
			cancelTimeout(timeoutTask);
		}

		Throwable callbackThrowable = callbackThrowableReference.get();

		if (callbackThrowable != null) {
			if (callbackThrowable instanceof Exception exception)
				throw exception;

			throw new RuntimeException(callbackThrowable);
		}

		return requestResultReference.get();
	}

	private TimeoutScheduler.@Nullable ScheduledTask scheduleTimeout(@NonNull Socket socket,
																																	 @NonNull Thread handlerThread,
																																	 @NonNull AtomicBoolean timedOut) {
		requireNonNull(socket);
		requireNonNull(handlerThread);
		requireNonNull(timedOut);

		TimeoutScheduler requestHandlerTimeoutScheduler = this.requestHandlerTimeoutScheduler;

		if (requestHandlerTimeoutScheduler == null || requestHandlerTimeoutScheduler.isShutdown())
			return null;

		return requestHandlerTimeoutScheduler.schedule(() -> {
			timedOut.set(true);
			try {
				socket.close();
			} catch (IOException ignored) {
				// Nothing to do
			}
			handlerThread.interrupt();
		}, this.requestHandlerTimeout);
	}

	@NonNull
	private McpLiveConnection registerLiveConnection(@NonNull Socket socket,
																									 @NonNull Request request,
																									 @NonNull AtomicBoolean slotReserved) throws IOException {
		requireNonNull(socket);
		requireNonNull(request);
		requireNonNull(slotReserved);

		String sessionId = request.getHeader("MCP-Session-Id").orElseThrow(() ->
				new IllegalStateException("Missing MCP-Session-Id for live MCP stream"));
		McpLiveConnection connection = new McpLiveConnection(
				socket,
				request,
				sessionId,
				Instant.now(),
				new ArrayBlockingQueue<>(this.connectionQueueCapacity),
				slotReserved
		);

		this.liveConnectionsBySessionId.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(connection);
		return connection;
	}

	@NonNull
	@Override
	public Boolean publishSessionMessage(@NonNull String sessionId,
																			 @NonNull McpObject message) {
		requireNonNull(sessionId);
		requireNonNull(message);

		CopyOnWriteArrayList<McpLiveConnection> connections = this.liveConnectionsBySessionId.get(sessionId);

		if (connections == null || connections.isEmpty())
			return false;

		byte[] payload = McpEventStreamPayloads.fromMessage(message);

		List<McpLiveConnection> newestConnections = new ArrayList<>(connections);
		newestConnections.sort((left, right) -> right.establishedAt().compareTo(left.establishedAt()));

		for (McpLiveConnection connection : newestConnections) {

			if (connection.closing().get())
				continue;

			if (connection.writeQueue().offer(WriteQueueElement.payload(payload)))
				return true;

			closeLiveConnection(connection, StreamTerminationReason.WRITE_FAILED,
					new IllegalStateException("MCP stream write queue is full"), true);
		}

		return false;
	}

	private void startConnectionProcessor(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);

		ExecutorService connectionExecutorService = this.connectionExecutorService;

		if (connectionExecutorService == null || connectionExecutorService.isShutdown()) {
			closeLiveConnection(connection, StreamTerminationReason.SERVER_STOPPING, null, true);
			return;
		}

		try {
			connectionExecutorService.execute(() -> processConnection(connection));
		} catch (RejectedExecutionException e) {
			recordTransportFailure(MetricsCollector.TransportFailureReason.TASK_ERROR, e, "task_error");
			closeLiveConnection(connection, StreamTerminationReason.SERVER_STOPPING, e, true);
		}
	}

	private void processConnection(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);
		connection.processingThread().set(Thread.currentThread());

		Throwable throwable = null;

		try {
			while (!connection.closing().get()) {
				WriteQueueElement writeQueueElement = connection.writeQueue().poll(this.heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);

				if (writeQueueElement == null)
					writeQueueElement = WriteQueueElement.heartbeat();

				if (writeQueueElement.isPoisonPill())
					break;

				writePayload(connection, writeQueueElement.bytes());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (!connection.closing().get())
				throwable = e;
		} catch (Throwable t) {
			throwable = t;
			if (shouldRecordStreamWriteFailure(connection))
				recordWriteTransportFailure(t);
		} finally {
			finishConnection(connection, throwable);
		}
	}

	private boolean shouldRecordStreamWriteFailure(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);

		StreamTerminationReason terminationReason = connection.terminationReason().get();
		return terminationReason == null || terminationReason == StreamTerminationReason.WRITE_FAILED;
	}

	private void writePayload(@NonNull McpLiveConnection connection,
														@NonNull byte[] payload) throws IOException {
		requireNonNull(connection);
		requireNonNull(payload);

		ReentrantLock outputLock = connection.outputLock();
		performWriteWithTimeout(connection.socket(), () -> {
			outputLock.lock();
			try {
				OutputStream outputStream = connection.socket().getOutputStream();
				outputStream.write(payload);
				outputStream.flush();
			} finally {
				outputLock.unlock();
			}
		});
	}

	private void finishConnection(@NonNull McpLiveConnection connection,
																@Nullable Throwable throwable) {
		requireNonNull(connection);

		removeConnection(connection);
		releaseReservedConnectionSlot(connection.slotReserved());
		closeQuietly(connection.socket());

		if (!connection.notifyRuntimeOnClose().get())
			return;

		DefaultMcpRuntime runtime = this.mcpRuntime;

		if (runtime == null)
			return;

		StreamTerminationReason terminationReason = connection.terminationReason().get();

		if (terminationReason == null) {
			if (this.stopping)
				terminationReason = StreamTerminationReason.SERVER_STOPPING;
			else if (isRemoteClose(throwable))
				terminationReason = StreamTerminationReason.CLIENT_DISCONNECTED;
			else
				terminationReason = StreamTerminationReason.WRITE_FAILED;
		}

		runtime.handleTerminatedStream(connection.request(), connection.sessionId(), terminationReason, throwable);
	}

	private void closeLiveConnection(@NonNull McpLiveConnection connection,
																	 @NonNull StreamTerminationReason terminationReason,
																	 @Nullable Throwable throwable,
																	 @NonNull Boolean notifyRuntimeOnClose) {
		requireNonNull(connection);
		requireNonNull(terminationReason);
		requireNonNull(notifyRuntimeOnClose);

		if (!connection.closing().compareAndSet(false, true))
			return;

		connection.terminationReason().compareAndSet(null, terminationReason);
		connection.terminationThrowable().compareAndSet(null, throwable);
		connection.notifyRuntimeOnClose().set(notifyRuntimeOnClose);
		connection.writeQueue().offer(WriteQueueElement.poisonPillInstance());
		closeQuietly(connection.socket());

		Thread processingThread = connection.processingThread().get();

		if (processingThread != null)
			processingThread.interrupt();
	}

	private void removeConnection(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);

		this.liveConnectionsBySessionId.computeIfPresent(connection.sessionId(), (sessionId, connections) -> {
			connections.remove(connection);
			return connections.isEmpty() ? null : connections;
		});
	}

	private void markConnectionEstablished(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);

		CopyOnWriteArrayList<McpLiveConnection> connections = this.liveConnectionsBySessionId.get(connection.sessionId());

		if (connections == null)
			return;

		if (connections.remove(connection))
			connections.add(connection);
	}

	private boolean sessionAllowsLiveGetStream(@NonNull String sessionId) {
		requireNonNull(sessionId);

		McpStoredSession storedSession = this.sessionStore.findBySessionId(sessionId).orElse(null);
		return storedSession != null
				&& storedSession.terminatedAt() == null
				&& storedSession.initialized()
				&& storedSession.initializedNotificationReceived();
	}

	private boolean isLiveEventStreamResponse(@NonNull Request request,
																						@NonNull HttpRequestResult requestResult) {
		requireNonNull(request);
		requireNonNull(requestResult);

		if (request.getHttpMethod() != HttpMethod.GET)
			return false;

		Set<String> contentTypeHeaderValues = requestResult.getMarshaledResponse().getHeaders().get("Content-Type");

		if (contentTypeHeaderValues == null || contentTypeHeaderValues.isEmpty())
			return false;

		for (String contentTypeHeaderValue : contentTypeHeaderValues) {
			if (contentTypeHeaderValue != null && contentTypeHeaderValue.toLowerCase(Locale.ENGLISH).startsWith("text/event-stream"))
				return true;
		}

		return false;
	}

	@NonNull
	private Request readRequest(@NonNull Socket socket) throws IOException {
		requireNonNull(socket);

		InputStream inputStream = socket.getInputStream();
		InetSocketAddress remoteAddress = remoteAddress(socket);
		ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(Math.min(this.maximumRequestSizeInBytes, 4096));
		byte[] buffer = new byte[this.requestReadBufferSizeInBytes];
		int headerEndIndex = -1;
		int bodyPrefixLength = 0;

		while (headerEndIndex == -1) {
			int bytesRead;

			try {
				bytesRead = inputStream.read(buffer);
			} catch (SocketTimeoutException e) {
				throw requestReadTimeoutException(e, headerBytes.size() > 0);
			}

			if (bytesRead == -1)
				throw new EOFException("Client closed the connection before the request was complete");

			headerBytes.write(buffer, 0, bytesRead);

			if (headerBytes.size() > this.maximumRequestSizeInBytes)
				throw new RequestTooLargeException(RequestTooLargeException.Reason.CONTENT);

			byte[] accumulated = headerBytes.toByteArray();
			headerEndIndex = endOfHeaders(accumulated);
			int headerMeasurementEndIndex = headerEndIndex == -1 ? accumulated.length : headerEndIndex;

			if (headerSectionLengthInBytes(accumulated, headerMeasurementEndIndex) > this.maximumHeadersSizeInBytes)
				throw new RequestTooLargeException(RequestTooLargeException.Reason.HEADERS);

			if (headerEndIndex != -1)
				bodyPrefixLength = accumulated.length - headerEndIndex;
		}

		byte[] accumulated = headerBytes.toByteArray();
		String headerSection = new String(accumulated, 0, headerEndIndex, StandardCharsets.ISO_8859_1);
		ParsedStartLineAndHeaders parsedStartLineAndHeaders = parseStartLineAndHeaders(headerSection);
		long contentLength = contentLength(parsedStartLineAndHeaders.headers());

		if (contentLength > Integer.MAX_VALUE)
			throw new IllegalRequestException("Request body is too large");

		if (contentLength > this.maximumRequestSizeInBytes)
			return buildRequest(parsedStartLineAndHeaders, remoteAddress, null, true);

		byte[] body = null;
		int requiredBodyLength = (int) contentLength;

		if (requiredBodyLength > 0) {
			socket.setSoTimeout(Math.toIntExact(Math.max(1L, this.requestBodyTimeout.toMillis())));
			body = new byte[requiredBodyLength];
			int copied = Math.min(bodyPrefixLength, requiredBodyLength);

			if (copied > 0)
				System.arraycopy(accumulated, headerEndIndex, body, 0, copied);

			int offset = copied;

			while (offset < requiredBodyLength) {
				int bytesRead;

				try {
					bytesRead = inputStream.read(body, offset, requiredBodyLength - offset);
				} catch (SocketTimeoutException e) {
					throw requestReadTimeoutException(e, true);
				}

				if (bytesRead == -1)
					throw new EOFException("Client closed the connection before request body was complete");

				offset += bytesRead;
			}
		}

		return buildRequest(parsedStartLineAndHeaders, remoteAddress, body, false);
	}

	@NonNull
	private RequestReadTimeoutException requestReadTimeoutException(@NonNull SocketTimeoutException socketTimeoutException,
																																	boolean requestMadeProgress) {
		requireNonNull(socketTimeoutException);

		RequestReadTimeoutException requestReadTimeoutException =
				new RequestReadTimeoutException(socketTimeoutException.getMessage(), requestMadeProgress);
		requestReadTimeoutException.initCause(socketTimeoutException);
		return requestReadTimeoutException;
	}

	private boolean requestReadTimeoutMadeProgress(@NonNull SocketTimeoutException socketTimeoutException) {
		requireNonNull(socketTimeoutException);

		if (socketTimeoutException instanceof RequestReadTimeoutException requestReadTimeoutException)
			return requestReadTimeoutException.requestMadeProgress();

		return true;
	}

	@NonNull
	private Request buildRequest(@NonNull ParsedStartLineAndHeaders parsedStartLineAndHeaders,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable byte[] body,
															 @NonNull Boolean contentTooLarge) {
		requireNonNull(parsedStartLineAndHeaders);
		requireNonNull(contentTooLarge);

		Request.RawBuilder requestBuilder = Request.withRawUrl(parsedStartLineAndHeaders.httpMethod(), parsedStartLineAndHeaders.rawUrl())
				.headers(parsedStartLineAndHeaders.headers())
				.remoteAddress(remoteAddress)
				.contentTooLarge(contentTooLarge);

		if (body != null && body.length > 0)
			requestBuilder.body(body);

		return requestBuilder.build();
	}

	@NonNull
	private ParsedStartLineAndHeaders parseStartLineAndHeaders(@NonNull String headerSection) throws RequestTooLargeException {
		requireNonNull(headerSection);

		List<String> lines = splitRequestLines(headerSection);
		HttpMethod httpMethod = null;
		String rawUrl = null;
		int hostHeaderCount = 0;
		int headerCount = 0;
		String hostHeaderValue = null;
		List<String> rawHeaderLines = new ArrayList<>();

		for (String rawLine : lines) {
			if (httpMethod == null) {
				String line = trimAggressivelyToNull(rawLine);

				if (line == null)
					continue;

				String[] components = line.trim().split("\\s+", -1);

				if (components.length != 3)
					throw new IllegalRequestException(format("Malformed MCP request line '%s'. Expected '<METHOD> <request-target> HTTP/1.1'", line));

				requireAsciiToken(components[0], "HTTP method");
				requireAsciiToken(components[1], "request target");
				requireAsciiToken(components[2], "HTTP version");

				if (components[1].length() > this.maximumRequestTargetLengthInBytes)
					throw new RequestTooLargeException(RequestTooLargeException.Reason.URI_TOO_LONG);

				if (!"HTTP/1.1".equalsIgnoreCase(components[2]))
					throw new IllegalRequestException(format("Unsupported HTTP version '%s' for MCP requests", components[2]));

				try {
					httpMethod = HttpMethod.valueOf(components[0].toUpperCase(Locale.ENGLISH));
				} catch (IllegalArgumentException e) {
					throw new IllegalRequestException(format("Unsupported HTTP method specified: '%s'", components[0]), e);
				}

				rawUrl = components[1];
				continue;
			}

			if (rawLine.isEmpty())
				break;

			if (rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t')
				throw new IllegalRequestException("Header folding is not supported for MCP requests");

			if (++headerCount > this.maximumHeaderCount)
				throw new RequestTooLargeException(RequestTooLargeException.Reason.HEADERS);

			int firstColonIndex = rawLine.indexOf(':');

			if (firstColonIndex <= 0)
				throw new IllegalRequestException(format("Malformed MCP request header line '%s'. Expected 'Header-Name: Value'", rawLine));

			String headerName = rawLine.substring(0, firstColonIndex);
			validateHeaderName(headerName);

			String headerValue = rawLine.substring(firstColonIndex + 1);
			validateHeaderValue(headerName, headerValue);

			if ("Host".equalsIgnoreCase(headerName)) {
				hostHeaderCount++;
				if (hostHeaderCount == 1)
					hostHeaderValue = headerValue;
			}

			rawHeaderLines.add(rawLine);
		}

		if (hostHeaderCount == 0)
			throw new IllegalRequestException("Missing Host header");

		if (hostHeaderCount > 1)
			throw new IllegalRequestException("Multiple Host headers are not allowed");

		if (!HostHeaderValidator.isValidHostHeaderValue(hostHeaderValue))
			throw new IllegalRequestException("Invalid Host header value");

		Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(rawHeaderLines);
		ensureNoTransferEncoding(headers);
		return new ParsedStartLineAndHeaders(httpMethod, rawUrl, headers);
	}

	private void ensureNoTransferEncoding(@NonNull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		Set<String> transferEncodingHeaderValues = headers.get("Transfer-Encoding");

		if (transferEncodingHeaderValues != null && !transferEncodingHeaderValues.isEmpty())
			throw new IllegalRequestException("Transfer-Encoding is not supported for MCP requests");
	}

	private long contentLength(@NonNull Map<String, Set<String>> headers) {
		requireNonNull(headers);

		Set<String> contentLengthHeaderValues = headers.get("Content-Length");

		if (contentLengthHeaderValues == null || contentLengthHeaderValues.isEmpty())
			return 0L;

		if (contentLengthHeaderValues.size() != 1)
			throw new IllegalRequestException("Multiple Content-Length headers are not allowed");

		String contentLengthHeaderValue = contentLengthHeaderValues.iterator().next();

		if (contentLengthHeaderValue == null || contentLengthHeaderValue.trim().isEmpty())
			throw new IllegalRequestException("Invalid Content-Length header value");

		try {
			long contentLength = Long.parseLong(contentLengthHeaderValue.trim());

			if (contentLength < 0)
				throw new IllegalRequestException("Invalid Content-Length header value");

			return contentLength;
		} catch (NumberFormatException e) {
			throw new IllegalRequestException("Invalid Content-Length header value", e);
		}
	}

	private int endOfHeaders(@NonNull byte[] bytes) {
		requireNonNull(bytes);

		for (int i = 0; i < bytes.length - 3; i++) {
			if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n')
				return i + 4;
		}

		for (int i = 0; i < bytes.length - 1; i++) {
			if (bytes[i] == '\n' && bytes[i + 1] == '\n')
				return i + 2;
		}

		return -1;
	}

	private int headerSectionLengthInBytes(byte @NonNull [] bytes,
																				 int endExclusive) {
		requireNonNull(bytes);

		int requestLineEndIndex = requestLineEndIndex(bytes, endExclusive);
		if (requestLineEndIndex == -1)
			return 0;

		return endExclusive - requestLineEndIndex;
	}

	private int requestLineEndIndex(byte @NonNull [] bytes,
																	int endExclusive) {
		requireNonNull(bytes);

		for (int i = 0; i < endExclusive; i++) {
			if (bytes[i] == '\n')
				return i + 1;
		}

		return -1;
	}

	private void writeAcceptedEventStreamResponse(@NonNull Socket socket,
																								@NonNull Request request,
																								@NonNull MarshaledResponse marshaledResponse) throws IOException {
		requireNonNull(socket);
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		writeObservedResponse(socket, request, marshaledResponse, () ->
				writeAcceptedEventStreamResponse(socket, marshaledResponse));
	}

	private void writeAcceptedEventStreamResponse(@NonNull Socket socket,
																								@NonNull MarshaledResponse marshaledResponse) throws IOException {
		requireNonNull(socket);
		requireNonNull(marshaledResponse);

		byte[] body = byteBackedBodyBytesOrNull(marshaledResponse);

		performWriteWithTimeout(socket, () -> {
			PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.ISO_8859_1);
			printWriter.print("HTTP/1.1 200 OK\r\n");

			for (Map.Entry<String, Set<String>> headerEntry : marshaledResponse.getHeaders().entrySet()) {
				String headerName = headerEntry.getKey();

				if (headerName == null)
					continue;

				String normalizedHeaderName = headerName.toLowerCase(Locale.ENGLISH);

				if ("content-length".equals(normalizedHeaderName) || "connection".equals(normalizedHeaderName))
					continue;

				for (String headerValue : normalizeHeaderValues(headerEntry.getValue()))
					printWriter.printf("%s: %s\r\n", headerName, headerValue);
			}

			for (ResponseCookie responseCookie : marshaledResponse.getCookies())
				printWriter.printf("Set-Cookie: %s\r\n", responseCookie.toSetCookieHeaderRepresentation());

			printWriter.print("\r\n");
			printWriter.flush();

			if (printWriter.checkError())
				throw new IOException("Unable to write MCP event stream response headers");

			if (body != null && body.length > 0) {
				OutputStream outputStream = socket.getOutputStream();
				outputStream.write(body);
				outputStream.flush();
			}
		});
	}

	private void writeMarshaledResponse(@NonNull Socket socket,
																		 @NonNull Request request,
																		 @NonNull MarshaledResponse marshaledResponse,
																		 @NonNull Boolean closeConnection) throws IOException {
		requireNonNull(socket);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(closeConnection);

		writeObservedResponse(socket, request, marshaledResponse, () ->
				writeMarshaledResponse(socket, marshaledResponse, closeConnection));
	}

	private void writeMarshaledResponse(@NonNull Socket socket,
																		 @NonNull MarshaledResponse marshaledResponse,
																		 @NonNull Boolean closeConnection) throws IOException {
		requireNonNull(socket);
		requireNonNull(marshaledResponse);
		requireNonNull(closeConnection);

		byte[] body = byteBackedBodyBytesOrNull(marshaledResponse);

		performWriteWithTimeout(socket, () -> {
			OutputStream outputStream = socket.getOutputStream();
			PrintWriter printWriter = new PrintWriter(outputStream, false, StandardCharsets.ISO_8859_1);
			Integer statusCode = marshaledResponse.getStatusCode();
			String reasonPhrase = StatusCode.fromStatusCode(statusCode).map(StatusCode::getReasonPhrase).orElse("Unknown");

			printWriter.printf("HTTP/1.1 %d %s\r\n", statusCode, reasonPhrase);

			boolean hasContentLength = false;
			boolean hasConnectionHeader = false;

			for (Map.Entry<String, Set<String>> headerEntry : marshaledResponse.getHeaders().entrySet()) {
				String headerName = headerEntry.getKey();

				if (headerName == null)
					continue;

				String normalizedHeaderName = headerName.toLowerCase(Locale.ENGLISH);

				if ("content-length".equals(normalizedHeaderName))
					hasContentLength = true;

				if ("connection".equals(normalizedHeaderName))
					hasConnectionHeader = true;

				for (String headerValue : normalizeHeaderValues(headerEntry.getValue()))
					printWriter.printf("%s: %s\r\n", headerName, headerValue);
			}

			for (ResponseCookie responseCookie : marshaledResponse.getCookies())
				printWriter.printf("Set-Cookie: %s\r\n", responseCookie.toSetCookieHeaderRepresentation());

			if (!hasContentLength)
				printWriter.printf("Content-Length: %d\r\n", marshaledResponse.getBodyLength());

			if (closeConnection && !hasConnectionHeader)
				printWriter.print("Connection: close\r\n");

			printWriter.print("\r\n");
			printWriter.flush();

			if (printWriter.checkError())
				throw new IOException("Unable to write MCP response headers");

			if (body != null && body.length > 0) {
				outputStream.write(body);
				outputStream.flush();
			}
		});
	}

	private void writeObservedResponse(@NonNull Socket socket,
																		 @NonNull Request request,
																		 @NonNull MarshaledResponse marshaledResponse,
																		 @NonNull IoWriteOperation writeOperation) throws IOException {
		requireNonNull(socket);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(writeOperation);

		notifyWillWriteResponse(request, marshaledResponse);

		long startedAtNanos = System.nanoTime();

		try {
			writeOperation.write();
		} catch (IOException e) {
			recordWriteTransportFailure(e);
			notifyDidFailToWriteResponse(request, marshaledResponse, Duration.ofNanos(System.nanoTime() - startedAtNanos), e);
			throw e;
		} catch (RuntimeException e) {
			recordWriteTransportFailure(e);
			notifyDidFailToWriteResponse(request, marshaledResponse, Duration.ofNanos(System.nanoTime() - startedAtNanos), e);
			throw e;
		} catch (Error e) {
			recordWriteTransportFailure(e);
			notifyDidFailToWriteResponse(request, marshaledResponse, Duration.ofNanos(System.nanoTime() - startedAtNanos), e);
			throw e;
		}

		notifyDidWriteResponse(request, marshaledResponse, Duration.ofNanos(System.nanoTime() - startedAtNanos));
	}

	private static @Nullable byte[] byteBackedBodyBytesOrNull(@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);

		if (body == null)
			return null;

		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return bytes.getBytes();

		throw new IllegalArgumentException(format(
				"MCP responses must be backed by byte arrays; received %s",
				body.getClass().getName()));
	}

	private void writePlainTextResponse(@NonNull Socket socket,
																			@NonNull Integer statusCode,
																			@NonNull String body) {
		requireNonNull(socket);
		requireNonNull(statusCode);
		requireNonNull(body);

		try {
			writeMarshaledResponse(socket, MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.body(body.getBytes(StandardCharsets.UTF_8))
					.build(), true);
		} catch (IOException e) {
			recordWriteTransportFailure(e);
		}
	}

	private void writePlainTextResponse(@NonNull Socket socket,
																			@NonNull Request request,
																			@NonNull Integer statusCode,
																			@NonNull String body) {
		requireNonNull(socket);
		requireNonNull(request);
		requireNonNull(statusCode);
		requireNonNull(body);

		try {
			writeMarshaledResponse(socket, request, MarshaledResponse.withStatusCode(statusCode)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
					.body(body.getBytes(StandardCharsets.UTF_8))
					.build(), true);
		} catch (IOException ignored) {
			// Nothing to do
		}
	}

	private void cleanupAfterFailedStart() {
		closeQuietly(this.serverSocket);

		if (this.requestHandlerExecutorService != null)
			this.requestHandlerExecutorService.shutdownNow();

		if (this.requestHandlerTimeoutScheduler != null)
			this.requestHandlerTimeoutScheduler.shutdownNow();

		if (this.connectionExecutorService != null)
			this.connectionExecutorService.shutdownNow();

		this.serverSocket = null;
		this.requestHandlerExecutorService = null;
		this.requestHandlerTimeoutScheduler = null;
		this.connectionExecutorService = null;
		this.acceptThread = null;
		this.started = false;
		this.stopping = false;
	}

	private void cleanupAfterUnexpectedAcceptLoopTermination(@NonNull Throwable throwable,
																													long lifecycleGeneration) {
		requireNonNull(throwable);

		ServerSocket serverSocketSnapshot;
		ExecutorService requestHandlerExecutorServiceSnapshot;
		TimeoutScheduler requestHandlerTimeoutSchedulerSnapshot;
		ExecutorService connectionExecutorServiceSnapshot;
		List<McpLiveConnection> liveConnectionsSnapshot = new ArrayList<>();
		boolean cleanupRequired = false;

		getLock().lock();

		try {
			if (this.started && this.lifecycleGeneration == lifecycleGeneration) {
				cleanupRequired = true;
				this.stopping = true;
				this.stopPoisonPill.set(true);
				serverSocketSnapshot = this.serverSocket;
				requestHandlerExecutorServiceSnapshot = this.requestHandlerExecutorService;
				requestHandlerTimeoutSchedulerSnapshot = this.requestHandlerTimeoutScheduler;
				connectionExecutorServiceSnapshot = this.connectionExecutorService;

				for (CopyOnWriteArrayList<McpLiveConnection> connections : new ArrayList<>(this.liveConnectionsBySessionId.values()))
					liveConnectionsSnapshot.addAll(connections);

				this.acceptThread = null;
				this.serverSocket = null;
				this.requestHandlerExecutorService = null;
				this.requestHandlerTimeoutScheduler = null;
				this.connectionExecutorService = null;
				this.started = false;
			} else {
				serverSocketSnapshot = null;
				requestHandlerExecutorServiceSnapshot = null;
				requestHandlerTimeoutSchedulerSnapshot = null;
				connectionExecutorServiceSnapshot = null;
			}
		} finally {
			getLock().unlock();
		}

		if (!cleanupRequired)
			return;

		try {
			notifyDidFailToAcceptConnection(null, ConnectionRejectionReason.INTERNAL_ERROR, throwable);
			safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "MCP accept loop terminated unexpectedly")
					.throwable(throwable)
					.build());
			recordTransportFailure(MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED, throwable, "event_loop_terminate");
			closeQuietly(serverSocketSnapshot);

			for (McpLiveConnection connection : liveConnectionsSnapshot)
				closeLiveConnection(connection, StreamTerminationReason.INTERNAL_ERROR, throwable, true);

			this.liveConnectionsBySessionId.clear();
			shutdownNowIfNeeded(requestHandlerExecutorServiceSnapshot);

			if (requestHandlerTimeoutSchedulerSnapshot != null)
				requestHandlerTimeoutSchedulerSnapshot.shutdownNow();

			shutdownNowIfNeeded(connectionExecutorServiceSnapshot);
			this.activeConnectionCount.set(0);
		} finally {
			ReentrantLock lock = getLock();
			lock.lock();

			try {
				if (this.lifecycleGeneration == lifecycleGeneration) {
					this.stopping = false;
					this.stopPoisonPill.set(false);
				}
			} finally {
				lock.unlock();
			}
		}
	}

	private void awaitPoolTermination(@Nullable ExecutorService executorService,
																		long timeoutMillis) {
		if (executorService == null)
			return;

		try {
			executorService.awaitTermination(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void shutdownNowIfNeeded(@Nullable ExecutorService executorService) {
		if (executorService != null && !executorService.isTerminated())
			executorService.shutdownNow();
	}

	@NonNull
	private CompletableFuture<Boolean> awaitTerminationAsync(@Nullable ExecutorService executorService,
																													 long timeoutMillis) {
		if (executorService == null || timeoutMillis <= 0L)
			return CompletableFuture.completedFuture(false);

		return CompletableFuture.supplyAsync(() -> {
			try {
				return executorService.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	@NonNull
	private CompletableFuture<Boolean> awaitTerminationAsync(@Nullable TimeoutScheduler timeoutScheduler,
																													 long timeoutMillis) {
		if (timeoutScheduler == null || timeoutMillis <= 0L)
			return CompletableFuture.completedFuture(false);

		return CompletableFuture.supplyAsync(() -> {
			try {
				return timeoutScheduler.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	@NonNull
	private CompletableFuture<Boolean> joinAsync(@Nullable Thread thread,
																							 long timeoutMillis) {
		if (thread == null || timeoutMillis <= 0L)
			return CompletableFuture.completedFuture(true);

		if (thread == Thread.currentThread())
			return CompletableFuture.completedFuture(true);

		return CompletableFuture.supplyAsync(() -> {
			try {
				thread.join(timeoutMillis);
				return !thread.isAlive();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	private void awaitSchedulerTermination(@Nullable TimeoutScheduler timeoutScheduler,
																				 long timeoutMillis) {
		if (timeoutScheduler == null)
			return;

		try {
			timeoutScheduler.awaitTermination(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void hardenJoin(@Nullable Thread thread,
													long timeoutMillis) {
		if (thread == null || thread == Thread.currentThread())
			return;

		try {
			thread.join(Math.max(0L, timeoutMillis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private long remainingMillis(long deadlineNanos) {
		return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
	}

	private void cancelTimeout(TimeoutScheduler.@Nullable ScheduledTask timeoutTask) {
		if (timeoutTask != null)
			timeoutTask.cancel();
	}

	private void performWriteWithTimeout(@NonNull Socket socket,
																			 @NonNull IoWriteOperation ioWriteOperation) throws IOException {
		requireNonNull(socket);
		requireNonNull(ioWriteOperation);

		if (this.writeTimeout.isZero()) {
			ioWriteOperation.write();
			return;
		}

		TimeoutScheduler timeoutScheduler = this.requestHandlerTimeoutScheduler;

		if (timeoutScheduler == null || timeoutScheduler.isShutdown()) {
			ioWriteOperation.write();
			return;
		}

		AtomicBoolean timedOut = new AtomicBoolean(false);
		TimeoutScheduler.ScheduledTask timeoutTask = timeoutScheduler.schedule(() -> {
			timedOut.set(true);
			closeQuietly(socket);
		}, this.writeTimeout);

		try {
			ioWriteOperation.write();
		} catch (IOException e) {
			if (timedOut.get()) {
				SocketTimeoutException socketTimeoutException = new SocketTimeoutException("MCP write timed out");
				socketTimeoutException.initCause(e);
				throw socketTimeoutException;
			}

			throw e;
		} finally {
			cancelTimeout(timeoutTask);
		}

		if (timedOut.get())
			throw new SocketTimeoutException("MCP write timed out");
	}

	@Nullable
	private InetSocketAddress remoteAddress(@NonNull Socket socket) {
		requireNonNull(socket);

		try {
			SocketAddress socketAddress = socket.getRemoteSocketAddress();
			return socketAddress instanceof InetSocketAddress inetSocketAddress ? inetSocketAddress : null;
		} catch (Exception ignored) {
			return null;
		}
	}

	private void safelyLog(@NonNull LogEvent logEvent) {
		requireNonNull(logEvent);

		SokletConfig sokletConfig = this.sokletConfig;

		if (sokletConfig == null)
			return;

		try {
			sokletConfig.getAggregateLifecycleObserver().didReceiveLogEvent(logEvent);
		} catch (Throwable throwable) {
			// The LifecycleObserver implementation errored out, but we can't let that affect us.
		}
	}

	private void recordTransportFailure(MetricsCollector.TransportFailureReason reason,
																			@Nullable Throwable throwable,
																			@NonNull String event) {
		requireNonNull(reason);
		requireNonNull(event);

		safelyLog(LogEvent.with(LogEventType.SERVER_TRANSPORT_FAILURE,
						format("MCP transport failure: %s", event))
				.throwable(throwable)
				.build());
		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didRecordTransportFailure", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didRecordTransportFailure(ServerType.MCP, reason, throwable));
	}

	private void recordWriteTransportFailure(@NonNull Throwable throwable) {
		requireNonNull(throwable);

		if (isRemoteClose(throwable) && !(throwable instanceof SocketTimeoutException))
			return;

		recordTransportFailure(
				transportFailureReasonForWrite(throwable),
				throwable,
				throwable instanceof SocketTimeoutException ? "write_timeout" : "write_error");
	}

	private static MetricsCollector.TransportFailureReason transportFailureReasonForWrite(@NonNull Throwable throwable) {
		requireNonNull(throwable);

		if (throwable instanceof SocketTimeoutException)
			return MetricsCollector.TransportFailureReason.WRITE_TIMEOUT;

		return MetricsCollector.TransportFailureReason.WRITE_ERROR;
	}

	private void safelyCollectMetrics(@NonNull String message,
																		@NonNull Consumer<MetricsCollector> metricsConsumer) {
		requireNonNull(message);
		requireNonNull(metricsConsumer);

		SokletConfig sokletConfig = this.sokletConfig;

		if (sokletConfig == null)
			return;

		try {
			metricsConsumer.accept(sokletConfig.getMetricsCollector());
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED, message)
					.throwable(throwable)
					.build());
		}
	}

	private void notifyWillAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().willAcceptConnection(ServerType.MCP, remoteAddress);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::willAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willAcceptConnection(ServerType.MCP, remoteAddressSnapshot));
	}

	private void notifyDidAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didAcceptConnection(ServerType.MCP, remoteAddress);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::didAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(throwable)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didAcceptConnection(ServerType.MCP, remoteAddressSnapshot));
	}

	private void notifyDidFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress,
																							 @NonNull ConnectionRejectionReason reason,
																							 @Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didFailToAcceptConnection(ServerType.MCP, remoteAddress, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_CONNECTION_FAILED,
							format("An exception occurred while invoking %s::didFailToAcceptConnection", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		ConnectionRejectionReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToAcceptConnection", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToAcceptConnection(ServerType.MCP,
						remoteAddressSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			 @Nullable String requestTarget) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().willAcceptRequest(ServerType.MCP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::willAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willAcceptRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																			@Nullable String requestTarget) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didAcceptRequest(ServerType.MCP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didAcceptRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToAcceptRequest(@Nullable InetSocketAddress remoteAddress,
																						@Nullable String requestTarget,
																						@NonNull RequestRejectionReason reason,
																						@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didFailToAcceptRequest(ServerType.MCP, remoteAddress, requestTarget, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_ACCEPT_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didFailToAcceptRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;
		RequestRejectionReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToAcceptRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToAcceptRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillReadRequest(@Nullable InetSocketAddress remoteAddress,
																		 @Nullable String requestTarget) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().willReadRequest(ServerType.MCP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::willReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willReadRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidReadRequest(@Nullable InetSocketAddress remoteAddress,
																		@Nullable String requestTarget) {
		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didReadRequest(ServerType.MCP, remoteAddress, requestTarget);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didReadRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot));
	}

	private void notifyDidFailToReadRequest(@Nullable InetSocketAddress remoteAddress,
																					@Nullable String requestTarget,
																					@NonNull RequestReadFailureReason reason,
																					@Nullable Throwable throwable) {
		requireNonNull(reason);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didFailToReadRequest(ServerType.MCP, remoteAddress, requestTarget, reason, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_FAIL_TO_READ_REQUEST_FAILED,
							format("An exception occurred while invoking %s::didFailToReadRequest", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.build());
		}

		InetSocketAddress remoteAddressSnapshot = remoteAddress;
		String requestTargetSnapshot = requestTarget;
		RequestReadFailureReason reasonSnapshot = reason;
		Throwable throwableSnapshot = throwable;

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToReadRequest", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToReadRequest(ServerType.MCP,
						remoteAddressSnapshot,
						requestTargetSnapshot,
						reasonSnapshot,
						throwableSnapshot));
	}

	private void notifyWillWriteResponse(@NonNull Request request,
																			 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().willWriteResponse(ServerType.MCP, request, null, marshaledResponse);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_RESPONSE_FAILED,
							format("An exception occurred while invoking %s::willWriteResponse", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.request(request)
					.build());
		}

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::willWriteResponse", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.willWriteResponse(ServerType.MCP, request, null, marshaledResponse));
	}

	private void notifyDidWriteResponse(@NonNull Request request,
																			@NonNull MarshaledResponse marshaledResponse,
																			@NonNull Duration responseWriteDuration) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(responseWriteDuration);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didWriteResponse(ServerType.MCP, request, null, marshaledResponse, responseWriteDuration);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
							format("An exception occurred while invoking %s::didWriteResponse", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.request(request)
					.build());
		}

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didWriteResponse", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didWriteResponse(ServerType.MCP, request, null, marshaledResponse, responseWriteDuration));
	}

	private void notifyDidFailToWriteResponse(@NonNull Request request,
																						@NonNull MarshaledResponse marshaledResponse,
																						@NonNull Duration responseWriteDuration,
																						@NonNull Throwable throwable) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(responseWriteDuration);
		requireNonNull(throwable);

		try {
			SokletConfig sokletConfig = this.sokletConfig;

			if (sokletConfig != null)
				sokletConfig.getAggregateLifecycleObserver().didFailToWriteResponse(ServerType.MCP, request, null, marshaledResponse, responseWriteDuration, throwable);
		} catch (Throwable t) {
			safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_RESPONSE_FAILED,
							format("An exception occurred while invoking %s::didFailToWriteResponse", LifecycleObserver.class.getSimpleName()))
					.throwable(t)
					.request(request)
					.build());
		}

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFailToWriteResponse", MetricsCollector.class.getSimpleName()),
				(metricsCollector) -> metricsCollector.didFailToWriteResponse(ServerType.MCP, request, null, marshaledResponse, responseWriteDuration, throwable));
	}

	@NonNull
	private static RequestRejectionReason rejectionReasonFor(@NonNull ExecutorService executorService) {
		requireNonNull(executorService);

		if (executorService.isShutdown() || executorService.isTerminated())
			return RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN;

		return RequestRejectionReason.REQUEST_HANDLER_QUEUE_FULL;
	}

	@NonNull
	private ReentrantLock getLock() {
		return this.lock;
	}

	@NonNull
	Integer getConcurrentConnectionLimit() {
		return this.concurrentConnectionLimit;
	}

	private static void closeQuietly(@Nullable AutoCloseable closeable) {
		if (closeable == null)
			return;

		try {
			closeable.close();
		} catch (Exception ignored) {
			// Nothing to do
		}
	}

	@NonNull
	private ExecutorService createExecutorService(@NonNull String threadNamePrefix,
																								@NonNull Integer threadPoolSize,
																								@NonNull Integer queueCapacity) {
		requireNonNull(threadNamePrefix);
		requireNonNull(threadPoolSize);
		requireNonNull(queueCapacity);

		if (Utilities.virtualThreadsAvailable()) {
			ThreadFactory threadFactory = Utilities.createVirtualThreadFactory(threadNamePrefix, (thread, throwable) -> {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR,
								"Unexpected exception occurred during MCP executor processing")
						.throwable(throwable)
						.build());
			});

			return new ThreadPoolExecutor(
					threadPoolSize,
					threadPoolSize,
					0L,
					TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(queueCapacity),
					threadFactory);
		}

		return new ThreadPoolExecutor(
				threadPoolSize,
				threadPoolSize,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				new DefaultHttpServer.NonvirtualThreadFactory(threadNamePrefix));
	}

	@NonNull
	private ExecutorService createConnectionExecutorService() {
		if (Utilities.virtualThreadsAvailable())
			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor("mcp-stream-", (thread, throwable) -> {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR,
								"Unexpected exception occurred during MCP stream connection processing")
						.throwable(throwable)
						.build());
			});

		return createExecutorService("mcp-stream-", this.requestHandlerConcurrency, this.connectionQueueCapacity);
	}

	private boolean reserveConnectionSlot(@NonNull AtomicBoolean slotReserved) {
		requireNonNull(slotReserved);

		if (this.concurrentConnectionLimit == 0)
			return true;

		while (true) {
			int current = this.activeConnectionCount.get();

			if (current >= this.concurrentConnectionLimit)
				return false;

			if (this.activeConnectionCount.compareAndSet(current, current + 1)) {
				slotReserved.set(true);
				return true;
			}
		}
	}

	private void releaseReservedConnectionSlot(@NonNull AtomicBoolean slotReserved) {
		requireNonNull(slotReserved);

		if (!slotReserved.getAndSet(false))
			return;

		this.activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
	}

	private void terminateRuntimeStreamIfPresent(@NonNull Request request,
																							@NonNull StreamTerminationReason terminationReason,
																							@Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(terminationReason);

		DefaultMcpRuntime runtime = this.mcpRuntime;
		String sessionId = request.getHeader("MCP-Session-Id").orElse(null);

		if (runtime != null && sessionId != null)
			runtime.handleTerminatedStream(request, sessionId, terminationReason, throwable);
	}

	private static boolean isRemoteClose(@Nullable Throwable throwable) {
		if (throwable == null)
			return false;

		Throwable current = throwable;

		while (current != null) {
			if (current instanceof EOFException)
				return true;

			if (current instanceof IOException) {
				String message = current.getMessage();

				if (message != null) {
					String normalized = message.toLowerCase(Locale.ROOT);

					if (normalized.contains("broken pipe")
							|| normalized.contains("connection reset")
							|| normalized.contains("connection aborted")
							|| normalized.contains("connection reset by peer")
							|| normalized.contains("software caused connection abort")
							|| normalized.contains("socket closed"))
						return true;
				}
			}

			current = current.getCause();
		}

		return false;
	}

	private static void requireAsciiToken(@NonNull String value,
																				@NonNull String field) {
		requireNonNull(value);
		requireNonNull(field);

		if (value.isEmpty())
			throw new IllegalRequestException(format("Missing %s in MCP request line", field));

		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) > 0x7F)
				throw new IllegalRequestException(format("Non-ASCII %s in MCP request line", field));
		}
	}

	private static void validateHeaderName(@NonNull String name) {
		requireNonNull(name);

		if (name.isEmpty())
			throw new IllegalRequestException("Header name is blank");

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);

			if (c > 0x7F || !isTchar(c))
				throw new IllegalRequestException(format("Illegal header name '%s'. Offending character: '%s'", name, Utilities.printableChar(c)));
		}
	}

	private static boolean isTchar(char c) {
		return c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+'
				|| c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~'
				|| Character.isLetterOrDigit(c);
	}

	private static void validateHeaderValue(@NonNull String name,
																					@NonNull String value) {
		requireNonNull(name);
		requireNonNull(value);

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (c == '\r' || c == '\n' || c == 0x00 || (c < 0x20 && c != '\t') || c == 0x7F)
				throw new IllegalRequestException(format("Illegal header value '%s' for header name '%s'. Offending character: '%s'", value, name, Utilities.printableChar(c)));
		}
	}

	@NonNull
	private static List<String> splitRequestLines(@NonNull String rawRequest) {
		requireNonNull(rawRequest);

		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (int i = 0; i < rawRequest.length(); i++) {
			char c = rawRequest.charAt(i);

			if (c == '\r') {
				lines.add(current.toString());
				current.setLength(0);

				if (i + 1 < rawRequest.length() && rawRequest.charAt(i + 1) == '\n')
					i++;

				continue;
			}

			if (c == '\n') {
				lines.add(current.toString());
				current.setLength(0);
				continue;
			}

			current.append(c);
		}

		lines.add(current.toString());
		return lines;
	}

	@NonNull
	private static List<String> normalizeHeaderValues(@Nullable Set<String> values) {
		if (values == null || values.isEmpty())
			return List.of();

		if (values instanceof SortedSet || values instanceof LinkedHashSet) {
			List<String> normalizedValues = new ArrayList<>(values.size());

			for (String value : values)
				normalizedValues.add(value == null ? "" : value);

			return normalizedValues;
		}

		SortedSet<String> sortedValues = new TreeSet<>();

		for (String value : values)
			sortedValues.add(value == null ? "" : value);

		return new ArrayList<>(sortedValues);
	}

	private record ParsedStartLineAndHeaders(
			@NonNull HttpMethod httpMethod,
			@NonNull String rawUrl,
			@NonNull Map<String, Set<String>> headers
	) {
		private ParsedStartLineAndHeaders {
			requireNonNull(httpMethod);
			requireNonNull(rawUrl);
			requireNonNull(headers);
		}
	}

	private record McpLiveConnection(
			@NonNull Socket socket,
			@NonNull Request request,
			@NonNull String sessionId,
			@NonNull Instant establishedAt,
			@NonNull BlockingQueue<@NonNull WriteQueueElement> writeQueue,
			@NonNull AtomicBoolean slotReserved,
			@NonNull AtomicBoolean closing,
			@NonNull AtomicReference<@Nullable StreamTerminationReason> terminationReason,
			@NonNull AtomicReference<@Nullable Throwable> terminationThrowable,
			@NonNull AtomicBoolean notifyRuntimeOnClose,
			@NonNull AtomicReference<@Nullable Thread> processingThread,
			@NonNull ReentrantLock outputLock
	) {
		private McpLiveConnection(@NonNull Socket socket,
															@NonNull Request request,
															@NonNull String sessionId,
															@NonNull Instant establishedAt,
															@NonNull BlockingQueue<@NonNull WriteQueueElement> writeQueue,
															@NonNull AtomicBoolean slotReserved) {
			this(socket, request, sessionId, establishedAt, writeQueue, slotReserved, new AtomicBoolean(false), new AtomicReference<>(),
					new AtomicReference<>(), new AtomicBoolean(true), new AtomicReference<>(), new ReentrantLock());
		}

		private McpLiveConnection {
			requireNonNull(socket);
			requireNonNull(request);
			requireNonNull(sessionId);
			requireNonNull(establishedAt);
			requireNonNull(writeQueue);
			requireNonNull(slotReserved);
			requireNonNull(closing);
			requireNonNull(terminationReason);
			requireNonNull(terminationThrowable);
			requireNonNull(notifyRuntimeOnClose);
			requireNonNull(processingThread);
			requireNonNull(outputLock);
		}
	}

	@FunctionalInterface
	private interface IoWriteOperation {
		void write() throws IOException;
	}

	private record WriteQueueElement(
			@NonNull byte[] bytes,
			@NonNull Boolean poisonPill
	) {
		private WriteQueueElement {
			requireNonNull(bytes);
			requireNonNull(poisonPill);
		}

		@NonNull
		private static WriteQueueElement payload(@NonNull byte[] bytes) {
			requireNonNull(bytes);
			return new WriteQueueElement(bytes, false);
		}

		@NonNull
		private static WriteQueueElement heartbeat() {
			return new WriteQueueElement(HEARTBEAT_BYTES, false);
		}

		@NonNull
		private static WriteQueueElement poisonPillInstance() {
			return new WriteQueueElement(emptyByteArray(), true);
		}

		private boolean isPoisonPill() {
			return this.poisonPill;
		}
	}

	private static final class RequestTooLargeException extends IOException {
		private enum Reason {
			CONTENT,
			HEADERS,
			URI_TOO_LONG
		}

		@NonNull
		private final Reason reason;

		private RequestTooLargeException(@NonNull Reason reason) {
			this.reason = requireNonNull(reason);
		}

		@NonNull
		private Reason reason() {
			return this.reason;
		}
	}

	private static final class RequestReadTimeoutException extends SocketTimeoutException {
		private static final long serialVersionUID = 1L;

		private final boolean requestMadeProgress;

		private RequestReadTimeoutException(@Nullable String message,
																				boolean requestMadeProgress) {
			super(message);
			this.requestMadeProgress = requestMadeProgress;
		}

		private boolean requestMadeProgress() {
			return this.requestMadeProgress;
		}
	}
}
