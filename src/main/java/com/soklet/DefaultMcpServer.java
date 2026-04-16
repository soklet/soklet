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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HANDLER_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
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
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_QUEUE_CAPACITY_MULTIPLIER = 64;
		DEFAULT_VIRTUAL_REQUEST_HANDLER_CONCURRENCY_MULTIPLIER = 16;
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024 * 64;
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 128;
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
		DEFAULT_WRITE_TIMEOUT = Duration.ZERO;
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
	private final IdGenerator<String> idGenerator;
	@NonNull
	private final Duration requestTimeout;
	@NonNull
	private final Duration requestHandlerTimeout;
	@NonNull
	private final Integer requestHandlerConcurrency;
	@NonNull
	private final Integer requestHandlerQueueCapacity;
	@NonNull
	private final Integer maximumRequestSizeInBytes;
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
	private volatile ScheduledExecutorService requestHandlerTimeoutExecutorService;
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
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.defaultInstance();
		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.requestHandlerTimeout = builder.requestHandlerTimeout != null ? builder.requestHandlerTimeout : DEFAULT_REQUEST_HANDLER_TIMEOUT;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : 0;
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

		if (this.requestReadBufferSizeInBytes < 1)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.connectionQueueCapacity < 1)
			throw new IllegalArgumentException("Connection queue capacity must be > 0");

		if (this.concurrentConnectionLimit < 0)
			throw new IllegalArgumentException("Concurrent connection limit must be >= 0");

		if (this.requestTimeout.isNegative() || this.requestTimeout.isZero())
			throw new IllegalArgumentException("Request timeout must be > 0");

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
		this.connectionExecutorServiceSupplier = () -> createExecutorService("mcp-stream-", this.requestHandlerConcurrency, this.connectionQueueCapacity);
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
			ScheduledThreadPoolExecutor timeoutExecutor = new ScheduledThreadPoolExecutor(1,
					new DefaultHttpServer.NonvirtualThreadFactory("mcp-request-timeout"));
			timeoutExecutor.setRemoveOnCancelPolicy(true);
			this.requestHandlerTimeoutExecutorService = timeoutExecutor;
			this.connectionExecutorService = this.connectionExecutorServiceSupplier.get();
			this.stopPoisonPill.set(false);
			this.stopping = false;
			this.started = true;

			Thread acceptThread = new Thread(this::acceptLoop, "mcp-accept-loop");
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
		ScheduledExecutorService requestHandlerTimeoutExecutorServiceSnapshot;
		ExecutorService connectionExecutorServiceSnapshot;

		getLock().lock();

		try {
			if (!this.started)
				return;

			this.stopping = true;
			this.stopPoisonPill.set(true);
			acceptThreadSnapshot = this.acceptThread;
			serverSocketSnapshot = this.serverSocket;
			requestHandlerExecutorServiceSnapshot = this.requestHandlerExecutorService;
			requestHandlerTimeoutExecutorServiceSnapshot = this.requestHandlerTimeoutExecutorService;
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
				closeLiveConnection(connection, McpStreamTerminationReason.SERVER_STOPPING, null, true);
		}

		this.liveConnectionsBySessionId.clear();

		if (requestHandlerExecutorServiceSnapshot != null)
			requestHandlerExecutorServiceSnapshot.shutdownNow();

		if (requestHandlerTimeoutExecutorServiceSnapshot != null)
			requestHandlerTimeoutExecutorServiceSnapshot.shutdownNow();

		if (connectionExecutorServiceSnapshot != null)
			connectionExecutorServiceSnapshot.shutdownNow();

		final long deadlineNanos = System.nanoTime() + this.shutdownTimeout.toNanos();

		try {
			hardenJoin(acceptThreadSnapshot, remainingMillis(deadlineNanos));
			awaitPoolTermination(requestHandlerExecutorServiceSnapshot, remainingMillis(deadlineNanos));
			awaitPoolTermination(requestHandlerTimeoutExecutorServiceSnapshot, remainingMillis(deadlineNanos));
			awaitPoolTermination(connectionExecutorServiceSnapshot, remainingMillis(deadlineNanos));
		} finally {
			getLock().lock();

			try {
				this.acceptThread = null;
				this.serverSocket = null;
				this.requestHandlerExecutorService = null;
				this.requestHandlerTimeoutExecutorService = null;
				this.connectionExecutorService = null;
				this.started = false;
				this.stopping = false;
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

	@NonNull
	@Override
	public IdGenerator<String> getIdGenerator() {
		return this.idGenerator;
	}

	void terminateStreamsForSession(@NonNull String sessionId) {
		requireNonNull(sessionId);

		CopyOnWriteArrayList<McpLiveConnection> connections = this.liveConnectionsBySessionId.remove(sessionId);

		if (connections == null)
			return;

		for (McpLiveConnection connection : connections)
			closeLiveConnection(connection, McpStreamTerminationReason.SESSION_TERMINATED, null, false);
	}

	private void acceptLoop() {
		ServerSocket serverSocket = this.serverSocket;

		if (serverSocket == null)
			return;

		while (!this.stopPoisonPill.get()) {
			try {
				Socket socket = serverSocket.accept();
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(Math.toIntExact(Math.max(1L, this.requestTimeout.toMillis())));
				submitRequest(socket);
			} catch (SocketException e) {
				if (this.stopPoisonPill.get())
					break;

				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "MCP accept loop encountered an IO error")
						.throwable(e)
						.build());
				} catch (IOException e) {
					if (this.stopPoisonPill.get())
						break;

					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "MCP accept loop encountered an IO error")
							.throwable(e)
							.build());
				} catch (RuntimeException e) {
					if (this.stopPoisonPill.get())
						break;

					safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "MCP accept loop encountered an unexpected error")
							.throwable(e)
							.build());
				}
			}
		}

	private void submitRequest(@NonNull Socket socket) throws IOException {
		requireNonNull(socket);

		ExecutorService requestHandlerExecutorService = this.requestHandlerExecutorService;

		if (requestHandlerExecutorService == null || requestHandlerExecutorService.isShutdown()) {
			socket.close();
			return;
		}

		try {
			requestHandlerExecutorService.submit(() -> handleSocket(socket));
		} catch (RejectedExecutionException e) {
			writePlainTextResponse(socket, 503, "Service unavailable");
			socket.close();
		}
	}

	private void handleSocket(@NonNull Socket socket) {
		requireNonNull(socket);

		Request request = null;
		boolean handedOffToStreamProcessor = false;
		AtomicBoolean connectionSlotReserved = new AtomicBoolean(false);

		try {
			request = readRequest(socket);
			HttpRequestResult requestResult = invokeRequestHandler(request, socket);

			if (requestResult == null) {
				writePlainTextResponse(socket, 500, "Internal server error");
				return;
			}

			if (isLiveEventStreamResponse(request, requestResult)) {
				McpLiveConnection liveConnection = null;

				try {
					if (!reserveConnectionSlot(connectionSlotReserved)) {
						writeMarshaledResponse(socket, serviceUnavailableResponse(request), true);
						return;
					}

					liveConnection = registerLiveConnection(socket, request, connectionSlotReserved);
					writeAcceptedEventStreamResponse(socket, requestResult.getMarshaledResponse());
					markConnectionEstablished(liveConnection);
					startConnectionProcessor(liveConnection);
					handedOffToStreamProcessor = true;
					return;
				} catch (Throwable throwable) {
					if (liveConnection != null) {
						releaseReservedConnectionSlot(liveConnection.slotReserved());
						removeConnection(liveConnection);
					}

					DefaultMcpRuntime runtime = this.mcpRuntime;
					String sessionId = request.getHeader("MCP-Session-Id").orElse(null);

					if (runtime != null && sessionId != null)
						runtime.handleTerminatedStream(request, sessionId,
								isRemoteClose(throwable) ? McpStreamTerminationReason.CLIENT_DISCONNECTED : McpStreamTerminationReason.WRITE_FAILED,
								throwable);

					if (throwable instanceof IOException ioException)
						throw ioException;

					throw throwable;
				}
			}

			writeMarshaledResponse(socket, requestResult.getMarshaledResponse(), true);

			if (request.getHttpMethod() == HttpMethod.DELETE
					&& Integer.valueOf(204).equals(requestResult.getMarshaledResponse().getStatusCode()))
				request.getHeader("MCP-Session-Id").ifPresent(this::terminateStreamsForSession);
		} catch (RequestTooLargeException e) {
			writePlainTextResponse(socket, 413, "Request entity too large");
		} catch (SocketTimeoutException e) {
			writePlainTextResponse(socket, 408, "Request timed out");
		} catch (IllegalRequestException e) {
			writePlainTextResponse(socket, 400, "Bad request");
		} catch (IOException e) {
			// client disconnected or write/read failed; nothing to do
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, "An unexpected error occurred during MCP request handling")
					.throwable(throwable)
					.request(request)
					.build());
			writePlainTextResponse(socket, 500, "Internal server error");
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
		ScheduledFuture<?> timeoutFuture = scheduleTimeout(socket, handlerThreadReference.get(), timedOut);

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
			cancelTimeout(timeoutFuture);
		}

		Throwable callbackThrowable = callbackThrowableReference.get();

		if (callbackThrowable != null) {
			if (callbackThrowable instanceof Exception exception)
				throw exception;

			throw new RuntimeException(callbackThrowable);
		}

		return requestResultReference.get();
	}

	@Nullable
	private ScheduledFuture<?> scheduleTimeout(@NonNull Socket socket,
																						 @NonNull Thread handlerThread,
																						 @NonNull AtomicBoolean timedOut) {
		requireNonNull(socket);
		requireNonNull(handlerThread);
		requireNonNull(timedOut);

		ScheduledExecutorService requestHandlerTimeoutExecutorService = this.requestHandlerTimeoutExecutorService;

		if (requestHandlerTimeoutExecutorService == null || requestHandlerTimeoutExecutorService.isShutdown())
			return null;

		return requestHandlerTimeoutExecutorService.schedule(() -> {
			timedOut.set(true);
			try {
				socket.close();
			} catch (IOException ignored) {
				// Nothing to do
			}
			handlerThread.interrupt();
		}, Math.max(1L, this.requestHandlerTimeout.toMillis()), TimeUnit.MILLISECONDS);
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

		for (int i = connections.size() - 1; i >= 0; i--) {
			McpLiveConnection connection = connections.get(i);

			if (connection.closing().get())
				continue;

			if (connection.writeQueue().offer(WriteQueueElement.payload(payload)))
				return true;

			closeLiveConnection(connection, McpStreamTerminationReason.WRITE_FAILED,
					new IllegalStateException("MCP stream write queue is full"), true);
		}

		return false;
	}

	private void startConnectionProcessor(@NonNull McpLiveConnection connection) {
		requireNonNull(connection);

		ExecutorService connectionExecutorService = this.connectionExecutorService;

		if (connectionExecutorService == null || connectionExecutorService.isShutdown()) {
			closeLiveConnection(connection, McpStreamTerminationReason.SERVER_STOPPING, null, true);
			return;
		}

		try {
			connectionExecutorService.submit(() -> processConnection(connection));
		} catch (RejectedExecutionException e) {
			closeLiveConnection(connection, McpStreamTerminationReason.SERVER_STOPPING, e, true);
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
		} finally {
			finishConnection(connection, throwable);
		}
	}

	private void writePayload(@NonNull McpLiveConnection connection,
														@NonNull byte[] payload) throws IOException {
		requireNonNull(connection);
		requireNonNull(payload);

		performWriteWithTimeout(connection.socket(), () -> {
			synchronized (connection.outputLock()) {
				OutputStream outputStream = connection.socket().getOutputStream();
				outputStream.write(payload);
				outputStream.flush();
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

		McpStreamTerminationReason terminationReason = connection.terminationReason().get();

		if (terminationReason == null) {
			if (this.stopping)
				terminationReason = McpStreamTerminationReason.SERVER_STOPPING;
			else if (isRemoteClose(throwable))
				terminationReason = McpStreamTerminationReason.CLIENT_DISCONNECTED;
			else
				terminationReason = McpStreamTerminationReason.WRITE_FAILED;
		}

		runtime.handleTerminatedStream(connection.request(), connection.sessionId(), terminationReason, throwable);
	}

	private void closeLiveConnection(@NonNull McpLiveConnection connection,
																	 @NonNull McpStreamTerminationReason terminationReason,
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
			int bytesRead = inputStream.read(buffer);

			if (bytesRead == -1)
				throw new EOFException("Client closed the connection before the request was complete");

			headerBytes.write(buffer, 0, bytesRead);

			if (headerBytes.size() > this.maximumRequestSizeInBytes)
				throw new RequestTooLargeException();

			byte[] accumulated = headerBytes.toByteArray();
			headerEndIndex = endOfHeaders(accumulated);

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
			body = new byte[requiredBodyLength];
			int copied = Math.min(bodyPrefixLength, requiredBodyLength);

			if (copied > 0)
				System.arraycopy(accumulated, headerEndIndex, body, 0, copied);

			int offset = copied;

			while (offset < requiredBodyLength) {
				int bytesRead = inputStream.read(body, offset, requiredBodyLength - offset);

				if (bytesRead == -1)
					throw new EOFException("Client closed the connection before request body was complete");

				offset += bytesRead;
			}
		}

		return buildRequest(parsedStartLineAndHeaders, remoteAddress, body, false);
	}

	@NonNull
	private Request buildRequest(@NonNull ParsedStartLineAndHeaders parsedStartLineAndHeaders,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable byte[] body,
															 @NonNull Boolean contentTooLarge) {
		requireNonNull(parsedStartLineAndHeaders);
		requireNonNull(contentTooLarge);

		Request.RawBuilder requestBuilder = Request.withRawUrl(parsedStartLineAndHeaders.httpMethod(), parsedStartLineAndHeaders.rawUrl())
				.idGenerator(this.idGenerator)
				.headers(parsedStartLineAndHeaders.headers())
				.remoteAddress(remoteAddress)
				.contentTooLarge(contentTooLarge);

		if (body != null && body.length > 0)
			requestBuilder.body(body);

		return requestBuilder.build();
	}

	@NonNull
	private ParsedStartLineAndHeaders parseStartLineAndHeaders(@NonNull String headerSection) {
		requireNonNull(headerSection);

		List<String> lines = splitRequestLines(headerSection);
		HttpMethod httpMethod = null;
		String rawUrl = null;
		int hostHeaderCount = 0;
		String hostHeaderValue = null;
		List<String> rawHeaderLines = new ArrayList<>();

		for (String rawLine : lines) {
			if (httpMethod == null) {
				String line = trimAggressivelyToNull(rawLine);

				if (line == null)
					continue;

				String[] components = line.trim().split("\\s+");

				if (components.length != 3)
					throw new IllegalRequestException(format("Malformed MCP request line '%s'. Expected '<METHOD> <request-target> HTTP/1.1'", line));

				requireAsciiToken(components[0], "HTTP method");
				requireAsciiToken(components[1], "request target");
				requireAsciiToken(components[2], "HTTP version");

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

	private void writeAcceptedEventStreamResponse(@NonNull Socket socket,
																								@NonNull MarshaledResponse marshaledResponse) throws IOException {
		requireNonNull(socket);
		requireNonNull(marshaledResponse);

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

			byte[] body = marshaledResponse.bodyBytesOrNull();

			if (body != null && body.length > 0) {
				OutputStream outputStream = socket.getOutputStream();
				outputStream.write(body);
				outputStream.flush();
			}
		});
	}

	private void writeMarshaledResponse(@NonNull Socket socket,
																		 @NonNull MarshaledResponse marshaledResponse,
																		 @NonNull Boolean closeConnection) throws IOException {
		requireNonNull(socket);
		requireNonNull(marshaledResponse);
		requireNonNull(closeConnection);

		performWriteWithTimeout(socket, () -> {
			byte[] body = marshaledResponse.bodyBytesOrEmpty();
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
				printWriter.printf("Content-Length: %d\r\n", body.length);

			if (closeConnection && !hasConnectionHeader)
				printWriter.print("Connection: close\r\n");

			printWriter.print("\r\n");
			printWriter.flush();

			if (printWriter.checkError())
				throw new IOException("Unable to write MCP response headers");

			if (body.length > 0) {
				outputStream.write(body);
				outputStream.flush();
			}
		});
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
		} catch (IOException ignored) {
			// Nothing to do
		}
	}

	private void cleanupAfterFailedStart() {
		closeQuietly(this.serverSocket);

		if (this.requestHandlerExecutorService != null)
			this.requestHandlerExecutorService.shutdownNow();

		if (this.requestHandlerTimeoutExecutorService != null)
			this.requestHandlerTimeoutExecutorService.shutdownNow();

		if (this.connectionExecutorService != null)
			this.connectionExecutorService.shutdownNow();

		this.serverSocket = null;
		this.requestHandlerExecutorService = null;
		this.requestHandlerTimeoutExecutorService = null;
		this.connectionExecutorService = null;
		this.acceptThread = null;
		this.started = false;
		this.stopping = false;
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

	private void hardenJoin(@Nullable Thread thread,
													long timeoutMillis) {
		if (thread == null)
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

	private void cancelTimeout(@Nullable ScheduledFuture<?> timeoutFuture) {
		if (timeoutFuture != null)
			timeoutFuture.cancel(false);
	}

	private void performWriteWithTimeout(@NonNull Socket socket,
																			 @NonNull IoWriteOperation ioWriteOperation) throws IOException {
		requireNonNull(socket);
		requireNonNull(ioWriteOperation);

		if (this.writeTimeout.isZero()) {
			ioWriteOperation.write();
			return;
		}

		ScheduledExecutorService timeoutExecutor = this.requestHandlerTimeoutExecutorService;

		if (timeoutExecutor == null || timeoutExecutor.isShutdown()) {
			ioWriteOperation.write();
			return;
		}

		AtomicBoolean timedOut = new AtomicBoolean(false);
		ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(() -> {
			timedOut.set(true);
			closeQuietly(socket);
		}, Math.max(1L, this.writeTimeout.toMillis()), TimeUnit.MILLISECONDS);

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
			cancelTimeout(timeoutFuture);
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
			sokletConfig.getLifecycleObserver().didReceiveLogEvent(logEvent);
		} catch (Throwable throwable) {
			throwable.printStackTrace(System.err);
		}
	}

	@NonNull
	private ReentrantLock getLock() {
		return this.lock;
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
	private static ExecutorService createExecutorService(@NonNull String threadNamePrefix,
																											 @NonNull Integer threadPoolSize,
																											 @NonNull Integer queueCapacity) {
		requireNonNull(threadNamePrefix);
		requireNonNull(threadPoolSize);
		requireNonNull(queueCapacity);

		if (Utilities.virtualThreadsAvailable()) {
			ThreadFactory threadFactory = Utilities.createVirtualThreadFactory(threadNamePrefix, (thread, throwable) -> {
				throwable.printStackTrace(System.err);
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
			@NonNull AtomicReference<@Nullable McpStreamTerminationReason> terminationReason,
			@NonNull AtomicReference<@Nullable Throwable> terminationThrowable,
			@NonNull AtomicBoolean notifyRuntimeOnClose,
			@NonNull AtomicReference<@Nullable Thread> processingThread,
			@NonNull Object outputLock
	) {
		private McpLiveConnection(@NonNull Socket socket,
															@NonNull Request request,
															@NonNull String sessionId,
															@NonNull Instant establishedAt,
															@NonNull BlockingQueue<@NonNull WriteQueueElement> writeQueue,
															@NonNull AtomicBoolean slotReserved) {
			this(socket, request, sessionId, establishedAt, writeQueue, slotReserved, new AtomicBoolean(false), new AtomicReference<>(),
					new AtomicReference<>(), new AtomicBoolean(true), new AtomicReference<>(), new Object());
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

	private static final class RequestTooLargeException extends IOException {}
}
