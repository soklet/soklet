/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.DefaultServerSentEventServer.ServerSentEventConnection.WriteQueueElement;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.internal.util.ConcurrentLruMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultServerSentEventServer implements ServerSentEventServer {
	@Nonnull
	private static final String DEFAULT_HOST;
	@Nonnull
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@Nonnull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@Nonnull
	private static final Duration DEFAULT_HEARTBEAT_INTERVAL;
	@Nonnull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;
	@Nonnull
	private static final Integer DEFAULT_CONNECTION_QUEUE_CAPACITY;
	@Nonnull
	private static final Integer DEFAULT_CONCURRENT_CONNECTION_LIMIT;
	@Nonnull
	private static final Integer DEFAULT_BROADCASTER_CACHE_CAPACITY;
	@Nonnull
	private static final Integer DEFAULT_RESOURCE_PATH_CACHE_CAPACITY;
	@Nonnull
	private static final Integer HEARTBEAT_BATCH_SIZE;
	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK;
	@Nonnull
	private static final ServerSentEvent SERVER_SENT_EVENT_POISON_PILL;
	@Nonnull
	private static final byte[] FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 1_024 * 1_024;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024;
		DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 256;
		DEFAULT_CONCURRENT_CONNECTION_LIMIT = 8_192;
		DEFAULT_BROADCASTER_CACHE_CAPACITY = 1_024;
		DEFAULT_RESOURCE_PATH_CACHE_CAPACITY = 8_192;
		HEARTBEAT_BATCH_SIZE = 1_000;

		// Make a unique "validity check" server-sent event used to wake a socket listener thread by injecting it into the relevant write queue.
		// When this event is taken off of the queue, a validity check is performed on the socket to see if it's still active.
		// If not, socket is torn down and the thread finishes running.
		// The contents don't matter; the object reference is used to determine if it's a validity check.
		SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK = ServerSentEvent.withEvent("validity-check").build();

		// Make a unique "poison pill" server-sent event used to stop a socket listener thread by injecting it into the relevant write queue.
		// When this event is taken off of the queue, the socket is torn down and the thread finishes running.
		// The contents don't matter; the object reference is used to determine if it's poison.
		SERVER_SENT_EVENT_POISON_PILL = ServerSentEvent.withEvent("poison").build();

		// Cache off a special failsafe response
		FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE = createFailsafeHandshakeHttp500Response();
	}

	@Nonnull
	private final Integer port;
	@Nonnull
	private final String host;
	@Nonnull
	private final Duration requestTimeout;
	@Nonnull
	private final Duration shutdownTimeout;
	@Nonnull
	private final Duration heartbeatInterval;
	@Nonnull
	private final Integer maximumRequestSizeInBytes;
	@Nonnull
	private final Integer requestReadBufferSizeInBytes;
	@Nonnull
	private final ConcurrentLruMap<ResourcePath, DefaultServerSentEventBroadcaster> broadcastersByResourcePath;
	@Nonnull
	private final ConcurrentLruMap<ResourcePath, ResourcePathDeclaration> resourcePathDeclarationsByResourcePathCache;
	@Nonnull
	private final ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> globalConnections;
	@Nonnull
	private final ReentrantLock lock;
	@Nonnull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@Nonnull
	private final Supplier<ExecutorService> requestReaderExecutorServiceSupplier;
	@Nonnull
	private final Integer concurrentConnectionLimit;
	@Nonnull
	private final Integer connectionQueueCapacity;
	@Nonnull
	private final AtomicBoolean stopPoisonPill;
	@Nonnull
	private final AtomicInteger heartbeatBatch;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ExecutorService requestReaderExecutorService;
	@Nullable
	private volatile ScheduledExecutorService connectionValidityExecutorService;
	@Nonnull
	private volatile Boolean started;
	@Nonnull
	private volatile Boolean stopping;
	@Nullable
	private Thread eventLoopThread;
	@Nullable
	private volatile ServerSocketChannel serverSocketChannel;
	// Does not need to be concurrent because it's calculated just once at initialization time and is never modified after
	@Nonnull
	private Map<ResourcePathDeclaration, ResourceMethod> resourceMethodsByResourcePathDeclaration;
	@Nullable
	private RequestHandler requestHandler;
	@Nullable
	private LifecycleInterceptor lifecycleInterceptor;

	@ThreadSafe
	protected static class DefaultServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@Nonnull
		private final ResourceMethod resourceMethod;
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final Consumer<ServerSentEventConnection> connectionUnregisteredListener;
		// This must be threadsafe, e.g. via ConcurrentHashMap#newKeySet
		@Nonnull
		private final Set<ServerSentEventConnection> serverSentEventConnections;

		public DefaultServerSentEventBroadcaster(@Nonnull ResourceMethod resourceMethod,
																						 @Nonnull ResourcePath resourcePath,
																						 @Nonnull Consumer<ServerSentEventConnection> connectionUnregisteredListener) {
			requireNonNull(resourceMethod);
			requireNonNull(resourcePath);
			requireNonNull(connectionUnregisteredListener);

			this.resourceMethod = resourceMethod;
			this.resourcePath = resourcePath;
			this.connectionUnregisteredListener = connectionUnregisteredListener;
			// TODO: let clients specify capacity
			this.serverSentEventConnections = ConcurrentHashMap.newKeySet(256);
		}

		@Nonnull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return (long) getServerSentEventConnections().size();
		}

		@Override
		public void broadcastEvent(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueServerSentEvent(serverSentEventConnection, serverSentEvent, EnqueueStrategy.DEFAULT);
		}

		@Override
		public void broadcastComment(@Nonnull String comment) {
			requireNonNull(comment);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (ServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueComment(serverSentEventConnection, comment);
		}

		@Nonnull
		public Boolean registerServerSentEventConnection(@Nullable ServerSentEventConnection serverSentEventConnection) {
			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			return getServerSentEventConnections().add(serverSentEventConnection);
		}

		@Nonnull
		public Boolean unregisterServerSentEventConnection(@Nullable ServerSentEventConnection serverSentEventConnection,
																											 @Nonnull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			boolean unregistered = getServerSentEventConnections().remove(serverSentEventConnection);

			if (unregistered) {
				getConnectionUnregisteredListener().accept(serverSentEventConnection);

				// If requested, send a poison pill so the socket thread gets terminated
				if (sendPoisonPill)
					enqueueServerSentEvent(serverSentEventConnection, SERVER_SENT_EVENT_POISON_PILL, EnqueueStrategy.DEFAULT);
			}

			return unregistered;
		}

		@Nonnull
		public void unregisterAllServerSentEventConnections(@Nonnull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			// Snapshot list for consistency during unregister process
			for (ServerSentEventConnection serverSentEventConnection : new ArrayList<>(getServerSentEventConnections()))
				unregisterServerSentEventConnection(serverSentEventConnection, sendPoisonPill);
		}

		@Nonnull
		protected Set<ServerSentEventConnection> getServerSentEventConnections() {
			return this.serverSentEventConnections;
		}

		@Nonnull
		protected Consumer<ServerSentEventConnection> getConnectionUnregisteredListener() {
			return this.connectionUnregisteredListener;
		}
	}

	protected DefaultServerSentEventServer(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.stopPoisonPill = new AtomicBoolean(false);
		this.started = false;
		this.stopping = false;
		this.lock = new ReentrantLock();
		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.heartbeatInterval = builder.heartbeatInterval != null ? builder.heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
		this.heartbeatBatch = new AtomicInteger(0);
		this.resourceMethodsByResourcePathDeclaration = Map.of(); // Temporary to remain non-null; will be overridden by Soklet via #initialize

		if (this.maximumRequestSizeInBytes <= 0)
			throw new IllegalArgumentException("Maximum request size must be > 0");

		if (this.requestReadBufferSizeInBytes <= 0)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.requestTimeout.isNegative() || this.requestTimeout.isZero())
			throw new IllegalArgumentException("Request timeout must be > 0");

		if (this.shutdownTimeout.isNegative())
			throw new IllegalArgumentException("Shutdown timeout must be >= 0");

		if (this.heartbeatInterval.isNegative() || this.heartbeatInterval.isZero())
			throw new IllegalArgumentException("Heartbeat interval must be > 0");

		this.broadcastersByResourcePath = new ConcurrentLruMap<>(builder.broadcasterCacheCapacity != null ? builder.broadcasterCacheCapacity : DEFAULT_BROADCASTER_CACHE_CAPACITY, (resourcePath, broadcaster) -> { /* nothing to do for now */});
		this.resourcePathDeclarationsByResourcePathCache = new ConcurrentLruMap<>(builder.resourcePathCacheCapacity != null ? builder.resourcePathCacheCapacity : DEFAULT_RESOURCE_PATH_CACHE_CAPACITY, (resourcePath, broadcaster) -> { /* nothing to do for now */});

		// Cowardly refuse to run on anything other than a runtime that supports Virtual threads.
		if (!Utilities.virtualThreadsAvailable())
			throw new IllegalStateException(format("Virtual threads are required for %s", getClass().getSimpleName()));

		this.requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier != null ? builder.requestHandlerExecutorServiceSupplier : () -> {
			String threadNamePrefix = "sse-request-handler-";

			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
				try {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server Server-Sent Event processing")
							.throwable(throwable)
							.build());
				} catch (Throwable loggingThrowable) {
					// We are in a bad state - the log operation in the uncaught exception handler failed.
					// Not much else we can do here but dump to stderr and try to stop the server.
					throwable.printStackTrace();
					loggingThrowable.printStackTrace();
				}
			});
		};

		this.requestReaderExecutorServiceSupplier = () -> {
			String threadNamePrefix = "sse-request-reader-";

			return Utilities.createVirtualThreadsNewThreadPerTaskExecutor(threadNamePrefix, (Thread thread, Throwable throwable) -> {
				try {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unexpected exception occurred during server Server-Sent Event request reading")
							.throwable(throwable)
							.build());
				} catch (Throwable loggingThrowable) {
					// We are in a bad state - the log operation in the uncaught exception handler failed.
					// Not much else we can do here but dump to stderr and try to stop the server.
					throwable.printStackTrace();
					loggingThrowable.printStackTrace();
				}
			});
		};

		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : DEFAULT_CONCURRENT_CONNECTION_LIMIT;

		if (this.concurrentConnectionLimit < 1)
			throw new IllegalArgumentException("The value for concurrentConnectionLimit must be > 0");

		this.connectionQueueCapacity = builder.connectionQueueCapacity != null ? builder.connectionQueueCapacity : DEFAULT_CONNECTION_QUEUE_CAPACITY;

		if (this.connectionQueueCapacity < 1)
			throw new IllegalArgumentException("The value for connectionQueueCapacity must be > 0");

		// Initialize the global LRU map with the specified limit. Assume ConcurrentLRUMap supports a removal listener.
		this.globalConnections = new ConcurrentLruMap<>(getConcurrentConnectionLimit(), (evictedConnection, broadcaster) -> {
			// This callback is triggered when a connection is evicted from the global LRU map.
			// Unregister the evicted connection from the broadcaster and send poison pill to close it.
			broadcaster.unregisterServerSentEventConnection(evictedConnection, true);
		});
	}

	@Override
	public void initialize(@Nonnull SokletConfig sokletConfig,
												 @Nonnull RequestHandler requestHandler) {
		requireNonNull(sokletConfig);
		requireNonNull(requestHandler);

		this.lifecycleInterceptor = sokletConfig.getLifecycleInterceptor();
		this.requestHandler = requestHandler;

		// Pick out all the @ServerSentEventSource resource methods and store off keyed on resource path for ease of lookup.
		// This is computed just once here and will never change.
		// Fail fast if there are duplicates for the same declaration.
		this.resourceMethodsByResourcePathDeclaration =
				sokletConfig.getResourceMethodResolver().getResourceMethods().stream()
						.filter(ResourceMethod::isServerSentEventSource)
						.collect(Collectors.toMap(
								ResourceMethod::getResourcePathDeclaration,
								Function.identity(),
								(resourceMethod1, resourceMethod2) -> {
									throw new IllegalStateException(format("Multiple @%s methods mapped to the same resource path: %s",
											ServerSentEventSource.class.getSimpleName(), resourceMethod1.getResourcePathDeclaration()));
								}
						));
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			// Should never happen, this would already be set by the Soklet instance
			if (getRequestHandler().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", RequestHandler.class, getClass()));

			if (getLifecycleInterceptor().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", LifecycleInterceptor.class, getClass()));

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.requestReaderExecutorService = getRequestReaderExecutorServiceSupplier().get();
			this.stopping = false;
			this.started = true; // set before thread starts to avoid early exit races
			this.eventLoopThread = new Thread(this::startInternal, "sse-event-loop");
			this.eventLoopThread.start();

			this.connectionValidityExecutorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
				@Override
				@Nonnull
				public Thread newThread(@Nonnull Runnable runnable) {
					requireNonNull(runnable);
					return new Thread(runnable, "sse-connection-validator");
				}
			});

			if (this.connectionValidityExecutorService instanceof ScheduledThreadPoolExecutor scheduledThreadPoolExecutor) {
				// Do not run existing delayed or periodic tasks after shutdown
				scheduledThreadPoolExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
				scheduledThreadPoolExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
				// Clean up canceled tasks quickly
				scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
			}

			int initialDelayInSeconds = (int) Math.max(1, Math.min(5, getHeartbeatInterval().getSeconds()));
			long periodInSeconds = Math.max(1, getHeartbeatInterval().getSeconds());

			this.connectionValidityExecutorService.scheduleWithFixedDelay(() -> {
				try {
					performConnectionValidityTask();
				} catch (Throwable throwable) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Server-Sent Event connection validity checker encountered an error")
							.throwable(throwable)
							.build());
				}
			}, initialDelayInSeconds, periodInSeconds, TimeUnit.SECONDS);
		} finally {
			getLock().unlock();
		}
	}

	private enum EnqueueStrategy {
		DEFAULT,
		ONLY_IF_CAPACITY_EXISTS
	}

	@Nonnull
	private static Boolean enqueueServerSentEvent(@Nonnull ServerSentEventConnection serverSentEventConnection,
																								@Nonnull ServerSentEvent serverSentEvent,
																								@Nonnull EnqueueStrategy enqueueStrategy) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);
		requireNonNull(enqueueStrategy);

		BlockingQueue<WriteQueueElement> writeQueue = serverSentEventConnection.getWriteQueue();
		WriteQueueElement writeQueueElement = WriteQueueElement.withServerSentEvent(serverSentEvent);

		if (enqueueStrategy == EnqueueStrategy.ONLY_IF_CAPACITY_EXISTS)
			return writeQueue.offer(writeQueueElement);

		// Try to add without blocking
		if (writeQueue.offer(writeQueueElement))
			return true;

		// Queue is full - this is a critical backpressure situation. Drop oldest and retry
		// TODO: maybe have this configurable?  e.g. DROP_OLDEST, DROP_NEWEST, FAIL, ...
		@SuppressWarnings("unused")
		WriteQueueElement dropped = writeQueue.poll();
		boolean added = writeQueue.offer(writeQueueElement);

		// This is a race condition - another thread filled the slot. We've now lost BOTH the old and new events
		if (!added)
			throw new IllegalStateException("Failed to enqueue event after removing oldest - queue capacity issue");

		// Return false to indicate an event was dropped
		return false;
	}

	private static void enqueueComment(@Nonnull ServerSentEventConnection serverSentEventConnection,
																		 @Nonnull String comment) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(comment);

		BlockingQueue<WriteQueueElement> writeQueue = serverSentEventConnection.getWriteQueue();
		WriteQueueElement writeQueueElement = WriteQueueElement.withComment(comment);

		if (!writeQueue.offer(writeQueueElement)) {
			writeQueue.poll();
			writeQueue.offer(writeQueueElement);
		}
	}

	protected void performConnectionValidityTask() {
		Collection<DefaultServerSentEventBroadcaster> broadcasters = getBroadcastersByResourcePath().values();

		if (broadcasters.isEmpty())
			return;

		final long NOW_IN_NANOS = System.nanoTime();

		// Calculate total connections
		int totalConnections = broadcasters.stream()
				.mapToInt(b -> b.getServerSentEventConnections().size())
				.sum();

		if (totalConnections == 0)
			return;

		// For large connection counts, only process a subset per heartbeat
		// This spreads the CPU cost across multiple heartbeat intervals
		boolean shouldBatch = totalConnections > HEARTBEAT_BATCH_SIZE * 2;
		int batchSize = shouldBatch ? Math.max(HEARTBEAT_BATCH_SIZE, totalConnections / 10) : totalConnections;
		int startIndex = shouldBatch ? getHeartbeatBatch().getAndAdd(batchSize) % totalConnections : 0;

		int currentIndex = 0;
		int processed = 0;

		outer:
		for (DefaultServerSentEventBroadcaster broadcaster : broadcasters) {
			Set<ServerSentEventConnection> connections = broadcaster.getServerSentEventConnections();

			if (connections.isEmpty()) {
				getBroadcastersByResourcePath().remove(broadcaster.getResourcePath(), broadcaster);
				continue;
			}

			for (ServerSentEventConnection connection : connections) {
				// Skip connections not in this batch
				if (shouldBatch && (currentIndex < startIndex || processed >= batchSize)) {
					currentIndex++;
					continue;
				}

				currentIndex++;
				processed++;

				AtomicLong lastCheck = connection.getLastValidityCheckInNanos();
				long previousCheck = lastCheck.get();
				long elapsed = NOW_IN_NANOS - previousCheck;

				if (elapsed >= getHeartbeatInterval().toNanos()) {
					// If the queue is full, skip the heartbeat until the next go-round
					boolean enqueued = enqueueServerSentEvent(connection, SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK, EnqueueStrategy.ONLY_IF_CAPACITY_EXISTS);

					if (enqueued)
						lastCheck.set(NOW_IN_NANOS);
				}

				// Early exit if we've processed enough
				if (shouldBatch && processed >= batchSize)
					break outer;
			}
		}
	}

	protected void startInternal() {
		if (!isStarted() || isStopping())
			return;

		ServerSocketChannel serverSocketChannel = null;

		try {
			serverSocketChannel = ServerSocketChannel.open();
			serverSocketChannel.bind(new InetSocketAddress(getHost(), getPort()));
			this.serverSocketChannel = serverSocketChannel;

			ExecutorService executorService = getRequestHandlerExecutorService().orElse(null);

			if (executorService == null || executorService.isShutdown() || isStopping())
				// We started while a stop was underway; exit quietly
				return;

			while (!getStopPoisonPill().get()) {
				SocketChannel clientSocketChannel = this.serverSocketChannel.accept();
				Socket socket = clientSocketChannel.socket();
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);

				try {
					executorService.submit(() -> handleClientSocketChannel(clientSocketChannel));
				} catch (RejectedExecutionException e) {
					// Pool is shutting down; close channel and exit the loop
					try {
						clientSocketChannel.close();
					} catch (IOException ignored) {
						// Nothing to do
					}

					break;
				}
			}
		} catch (ClosedChannelException ignored) {
			// expected during shutdown
		} catch (IOException e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
					"SSE event loop encountered an IO error").throwable(e).build());
		} finally {
			// Close the server socket if we opened it
			ServerSocketChannel serverSocketChannelToClose = this.serverSocketChannel;
			this.serverSocketChannel = null;

			if (serverSocketChannelToClose != null) {
				try {
					serverSocketChannelToClose.close();
				} catch (IOException ignored) {
					// Nothing to do
				}
			}
		}
	}

	protected void handleClientSocketChannel(@Nonnull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		ClientSocketChannelRegistration clientSocketChannelRegistration = null;
		Request request = null;
		ResourceMethod resourceMethod = null;
		Instant writeStarted;
		Throwable throwable = null;

		try (clientSocketChannel) {
			// Use the socket's address as an identifier
			String requestIdentifier = clientSocketChannel.getRemoteAddress().toString();

			try {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "will/didInitiateConnection"
				String rawRequest = readRequest(requestIdentifier, clientSocketChannel);
				request = parseRequest(requestIdentifier, rawRequest);
			} catch (URISyntaxException e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_HANDSHAKE_REQUEST, format("Unable to parse Server-Sent Event request URI: %s", e.getInput()))
						.throwable(e)
						.build());
				throw e;
			} catch (RequestTooLargeIOException e) {
				// Exception provides a "too large"-flagged request with whatever data we could pull out of it
				request = e.getTooLargeRequest();
			} catch (SocketTimeoutException e) {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "request timed out"
				throw e;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_HANDSHAKE_REQUEST, "Unable to parse Server-Sent Event request")
						.throwable(e)
						.build());
				throw e;
			}

			// OK, we've successfully parsed the SSE handshake request - now, determine the resource path
			ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(request.getResourcePath()).orElse(null);

			if (resourcePathDeclaration != null)
				resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

			// Keep track of whether or not we should go through the "handshake accepted" flow.
			// Might change from "accepted" to "rejected" if an error occurs
			AtomicReference<HandshakeResult.Accepted> handshakeAcceptedReference = new AtomicReference<>();

			// We're now ready to write the handshake response - and then we keep the socket open for subsequent writes if handshake was accepted (otherwise we write the body and close).
			// To write the handshake response, we delegate to the Soklet instance, handing it the request we just parsed
			// and receiving a MarshaledResponse to write.  This lets the normal Soklet request processing flow occur.
			// Subsequent writes to the open socket (those following successful transmission of the "accepted" handshake response) are done via a ServerSentEventBroadcaster and sidestep the Soklet request processing flow.
			getRequestHandler().get().handleRequest(request, (@Nonnull RequestResult requestResult) -> {
				// Set to the value Soklet processing gives us. Will be the empty Optional if no resource method was matched
				HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

				// Store a reference to the accepted handshake if we have it
				if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted accepted)
					handshakeAcceptedReference.set(accepted);

				byte[] handshakeHttpResponse;

				try {
					handshakeHttpResponse = createHandshakeHttpResponse(requestResult);
				} catch (Throwable t) {
					// Should not happen, but if it does, we fall back to "rejected" handshake mode and write a failsafe 500 response
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to generate SSE handshake response")
							.throwable(t)
							.build());

					// Clear the accepted handshake reference in case it was set
					handshakeAcceptedReference.set(null);
					handshakeHttpResponse = FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;
				}

				try {
					ByteBuffer byteBuffer = ByteBuffer.wrap(handshakeHttpResponse);

					while (byteBuffer.hasRemaining())
						clientSocketChannel.write(byteBuffer);
				} catch (Throwable t) {
					// We couldn't write a response to the client (maybe they disconnected).
					// Go through the rejected flow and close out the connection
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_FAILED_WRITING_HANDSHAKE_RESPONSE, "Unable to write SSE handshake response")
							.throwable(t)
							.build());

					// Clear the accepted handshake reference in case it was set
					handshakeAcceptedReference.set(null);
				}
			});

			// Happy path: register the channel for future ServerSentEvent writes and keep it open.
			// Otherwise, we're done immediately now that initial data has been written - shut it all down.
			HandshakeResult.Accepted handshakeAccepted = handshakeAcceptedReference.get();

			if (handshakeAccepted != null) {
				getLifecycleInterceptor().get().willEstablishServerSentEventConnection(request, resourceMethod);

				// If there is a client initializer, invoke it immediately prior to finalizing the SSE connection
				Consumer<ServerSentEventUnicaster> clientInitializer = handshakeAccepted.getClientInitializer().orElse(null);
				clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request, clientInitializer).get();

				getLifecycleInterceptor().get().didEstablishServerSentEventConnection(request, resourceMethod);

				while (true) {
					// Wait for SSE broadcasts on socket
					WriteQueueElement writeQueueElement = clientSocketChannelRegistration.serverSentEventConnection().getWriteQueue().take();
					ServerSentEvent serverSentEvent = writeQueueElement.getServerSentEvent().orElse(null);
					String comment = writeQueueElement.getComment().orElse(null);

					if (serverSentEvent == SERVER_SENT_EVENT_POISON_PILL) {
						// Encountered poison pill, exit...
						break;
					}

					String payload;

					if (serverSentEvent == SERVER_SENT_EVENT_CONNECTION_VALIDITY_CHECK) {
						// Perform socket validity check by writing a heartbeat
						payload = formatCommentForResponse("");
					} else if (serverSentEvent != null) {
						// It's a normal server-sent event
						payload = formatServerSentEventForResponse(serverSentEvent);
					} else if (comment != null) {
						// It's a comment
						payload = formatCommentForResponse(comment);
					} else {
						throw new IllegalStateException("Not sure what to do; no Server-Sent Event or comment available");
					}

					ByteBuffer byteBuffer = ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));

					if (serverSentEvent != null)
						getLifecycleInterceptor().get().willStartServerSentEventWriting(request,
								clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent);

					writeStarted = Instant.now();
					Throwable writeThrowable = null;

					try {
						while (byteBuffer.hasRemaining()) {
							clientSocketChannel.write(byteBuffer);
						}
					} catch (Throwable t) {
						writeThrowable = t;
					} finally {
						Instant writeFinished = Instant.now();
						Duration writeDuration = Duration.between(writeStarted, writeFinished);

						if (serverSentEvent != null)
							getLifecycleInterceptor().get().didFinishServerSentEventWriting(request,
									clientSocketChannelRegistration.serverSentEventConnection().getResourceMethod(), serverSentEvent, writeDuration, writeThrowable);

						if (writeThrowable != null)
							throw writeThrowable;
					}
				}
			} else {
				// Nothing to do for the moment - handshake was rejected, we're not keeping the socket open - shut it down!
			}
		} catch (Throwable t) {
			throwable = t;

			// TODO: log out exception that caused the socket to close?

			if (t instanceof InterruptedException)
				Thread.currentThread().interrupt();
		} finally {
			// First, close the channel itself...
			if (clientSocketChannel != null) {
				try {
					clientSocketChannel.close();
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
									"Unable to close Server-Sent Event connection socket channel")
							.throwable(exception)
							.build());
				}
			}

			// ...then unregister from broadcaster (prevents race with broadcasts)
			if (clientSocketChannelRegistration != null) {
				if (resourceMethod != null)
					getLifecycleInterceptor().get().willTerminateServerSentEventConnection(request, resourceMethod, throwable);

				try {
					clientSocketChannelRegistration.broadcaster().unregisterServerSentEventConnection(
							clientSocketChannelRegistration.serverSentEventConnection(), false);
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to de-register Server-Sent Event connection")
							.throwable(exception)
							.build());
				}

				// Cleanup empty broadcaster
				maybeCleanupBroadcaster(clientSocketChannelRegistration.broadcaster());

				if (resourceMethod != null) {
					Instant connectionFinished = Instant.now();
					Duration connectionDuration = Duration.between(clientSocketChannelRegistration.serverSentEventConnection().getEstablishedAt(), connectionFinished);
					getLifecycleInterceptor().get().didTerminateServerSentEventConnection(request, resourceMethod, connectionDuration, throwable);
				}
			}
		}
	}

	@Nonnull
	protected byte[] createHandshakeHttpResponse(@Nonnull RequestResult requestResult) throws IOException {
		requireNonNull(requestResult);

		MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();
		HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

		// Shared buffer for building the header section
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);
				 OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
				 PrintWriter printWriter = new PrintWriter(outputStreamWriter, false)) {

			if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted) {
				final Set<String> ILLEGAL_LOWERCASE_HEADER_NAMES = Set.of("content-length", "transfer-encoding");

				// HTTP status line
				printWriter.print("HTTP/1.1 200 OK\r\n");

				// Write headers, ignoring illegal ones
				for (Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
					String headerName = entry.getKey();

					if (headerName == null)
						continue;

					if (ILLEGAL_LOWERCASE_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ENGLISH)))
						throw new IllegalArgumentException(format("You may not specify the '%s' header for %s.%s responses",
								headerName, HandshakeResult.class.getSimpleName(), HandshakeResult.Accepted.class.getSimpleName()));

					Set<String> values = entry.getValue();

					if (values != null)
						for (String value : values)
							printWriter.printf("%s: %s\r\n", headerName, value);
				}

				// Emit cookies (one header per cookie)
				for (ResponseCookie cookie : marshaledResponse.getCookies())
					printWriter.printf("Set-Cookie: %s\r\n", cookie.toSetCookieHeaderRepresentation());

				// Terminate header section
				printWriter.print("\r\n");
				printWriter.flush();

				// No body for SSE handshakes
				return outputStream.toByteArray();
			} else if (handshakeResult == null || handshakeResult instanceof HandshakeResult.Rejected) {
				// Status line
				int statusCode = marshaledResponse.getStatusCode();
				String reasonPhrase = StatusCode.fromStatusCode(statusCode).map(StatusCode::getReasonPhrase).orElse("");

				if (reasonPhrase.length() > 0)
					printWriter.printf("HTTP/1.1 %d %s\r\n", statusCode, reasonPhrase);
				else
					printWriter.printf("HTTP/1.1 %d\r\n", statusCode);

				// Write headers
				boolean hasContentLength = false;
				boolean hasTransferEncoding = false;

				for (Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
					String headerName = entry.getKey();

					if (headerName == null)
						continue;

					String lowercaseHeaderName = headerName.toLowerCase(Locale.ENGLISH);

					if (lowercaseHeaderName.equals("content-length"))
						hasContentLength = true;

					if (lowercaseHeaderName.equals("transfer-encoding"))
						hasTransferEncoding = true;

					Set<String> headerValues = entry.getValue();

					if (headerValues != null)
						for (String headerValue : headerValues)
							printWriter.printf("%s: %s\r\n", headerName, headerValue);
				}

				// Emit cookies (one header per cookie)
				for (ResponseCookie cookie : marshaledResponse.getCookies())
					printWriter.printf("Set-Cookie: %s\r\n", cookie.toSetCookieHeaderRepresentation());

				byte[] body = marshaledResponse.getBody().orElse(null);
				int bodyLength = (body == null ? 0 : body.length);

				// Add Content-Length if body is present and user didn’t set it
				if (bodyLength > 0 && !hasContentLength && !hasTransferEncoding)
					printWriter.printf("Content-Length: %d\r\n", bodyLength);

				// Default Connection: close (rejected handshakes do not remain open)
				printWriter.print("Connection: close\r\n");

				// End headers
				printWriter.print("\r\n");
				printWriter.flush();

				// Write headers + body
				if (body != null && body.length > 0)
					outputStream.write(body);

				return outputStream.toByteArray();
			} else {
				throw new IllegalStateException(String.format("Unsupported %s: %s", HandshakeResult.class.getSimpleName(), handshakeResult));
			}
		}
	}

	@Nonnull
	private static byte[] createFailsafeHandshakeHttp500Response() {
		String body = "HTTP 500: Internal Server Error";
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

		String response =
				"HTTP/1.1 500 Internal Server Error\r\n" +
						"Content-Type: text/plain; charset=UTF-8\r\n" +
						"Content-Length: " + bodyBytes.length + "\r\n" +
						"Connection: close\r\n" +
						"\r\n";

		byte[] headerBytes = response.getBytes(StandardCharsets.UTF_8);

		// Combine headers + body into a single byte array
		byte[] combined = new byte[headerBytes.length + bodyBytes.length];
		System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
		System.arraycopy(bodyBytes, 0, combined, headerBytes.length, bodyBytes.length);
		return combined;
	}

	@Nonnull
	protected String formatCommentForResponse(@Nonnull String comment) {
		requireNonNull(comment);

		String[] lines = comment.split("\\R", -1);

		if (lines.length == 0)
			return ":\n\n";

		StringBuilder stringBuilder = new StringBuilder();

		for (String line : lines) {
			stringBuilder.append(':');

			if (!line.isEmpty())
				stringBuilder.append(' ').append(line);

			stringBuilder.append('\n');
		}

		stringBuilder.append('\n');

		return stringBuilder.toString();
	}

	@Nonnull
	protected String formatServerSentEventForResponse(@Nonnull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEvent);

		String event = serverSentEvent.getEvent().orElse(null);

		String data = serverSentEvent.getData().orElse(null);
		List<String> dataLines = data == null
				? List.of()
				: Arrays.stream(data.split("\\R", -1)) // preserve trailing empties
				.map(line -> format("data: %s", line))
				.collect(Collectors.toList());

		String id = serverSentEvent.getId().orElse(null);
		Duration retry = serverSentEvent.getRetry().orElse(null);

		List<String> lines = new ArrayList<>(16);

		if (event != null)
			lines.add(format("event: %s", event));

		if (id != null)
			lines.add(format("id: %s", id));

		if (retry != null)
			lines.add(format("retry: %d", retry.toMillis()));

		if (dataLines.size() > 0)
			lines.addAll(dataLines);

		if (lines.size() == 0)
			return ":\n\n";

		return format("%s\n\n", lines.stream().collect(Collectors.joining("\n")));
	}

	@Nonnull
	protected String debuggingString(@Nonnull Request request) {
		requireNonNull(request);
		return format("%s %s %s", request.getId(), request.getHttpMethod().name(), request.getUri());
	}

	@ThreadSafe
	protected static final class ServerSentEventConnection {
		@Nonnull
		private final Request request;
		@Nonnull
		private final ResourceMethod resourceMethod;
		@Nonnull
		private final BlockingQueue<WriteQueueElement> writeQueue;
		@Nonnull
		private final Instant establishedAt;
		@Nonnull
		private final AtomicLong lastValidityCheckInNanos;

		@ThreadSafe
		static final class WriteQueueElement {
			@Nullable
			private final ServerSentEvent serverSentEvent;
			@Nullable
			private final String comment;

			@Nonnull
			public static WriteQueueElement withServerSentEvent(@Nonnull ServerSentEvent serverSentEvent) {
				requireNonNull(serverSentEvent);
				return new WriteQueueElement(serverSentEvent, null);
			}

			@Nonnull
			public static WriteQueueElement withComment(@Nonnull String comment) {
				requireNonNull(comment);
				return new WriteQueueElement(null, comment);
			}

			private WriteQueueElement(@Nullable ServerSentEvent serverSentEvent,
																@Nullable String comment) {
				if (serverSentEvent == null && comment == null)
					throw new IllegalStateException("Must provide either a server-sent event or a comment");

				if (serverSentEvent != null && comment != null)
					throw new IllegalStateException("Must provide either a server-sent event or a comment; not both");

				this.serverSentEvent = serverSentEvent;
				this.comment = comment;
			}

			@Nonnull
			public Optional<ServerSentEvent> getServerSentEvent() {
				return Optional.ofNullable(this.serverSentEvent);
			}

			@Nonnull
			public Optional<String> getComment() {
				return Optional.ofNullable(this.comment);
			}
		}

		public ServerSentEventConnection(@Nonnull Request request,
																		 @Nonnull ResourceMethod resourceMethod,
																		 @Nonnull Integer connectionQueueCapacity) {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(connectionQueueCapacity);

			this.request = request;
			this.resourceMethod = resourceMethod;
			this.writeQueue = new ArrayBlockingQueue<>(connectionQueueCapacity);
			this.establishedAt = Instant.now();
			this.lastValidityCheckInNanos = new AtomicLong(System.nanoTime());
		}

		@Nonnull
		public Request getRequest() {
			return this.request;
		}

		@Nonnull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Nonnull
		public BlockingQueue<WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}

		@Nonnull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@Nonnull
		public AtomicLong getLastValidityCheckInNanos() {
			return this.lastValidityCheckInNanos;
		}
	}

	protected record ClientSocketChannelRegistration(@Nonnull ServerSentEventConnection serverSentEventConnection,
																									 @Nonnull DefaultServerSentEventBroadcaster broadcaster) {
		public ClientSocketChannelRegistration {
			requireNonNull(serverSentEventConnection);
			requireNonNull(broadcaster);
		}
	}

	@Nonnull
	protected Optional<ClientSocketChannelRegistration> registerClientSocketChannel(@Nonnull SocketChannel clientSocketChannel,
																																									@Nonnull Request request,
																																									@Nullable Consumer<ServerSentEventUnicaster> clientInitializer) {
		requireNonNull(clientSocketChannel);
		requireNonNull(request);

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourcePath resourcePath = request.getResourcePath();

		if (!matchingResourcePath(resourcePath).isPresent())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		// Create the connection and register it with the EventSource
		ServerSentEventConnection serverSentEventConnection = new ServerSentEventConnection(request, resourceMethod, getConnectionQueueCapacity());

		// If a client initializer exists, hand it the unicaster to support Last-Event-ID "catch up" scenarios
		ServerSentEventUnicaster serverSentEventUnicaster = new DefaultServerSentEventUnicaster(resourcePath, serverSentEventConnection.getWriteQueue());

		if (clientInitializer != null)
			clientInitializer.accept(serverSentEventUnicaster);

		// TODO: introduce this in a subsequent release.  Will need to make it configurable or rework some SSE tests to ignore the initial message
		// Now that the client initializer has run (if present), enqueue a single "heartbeat" comment to immediately "flush"/verify the connection
		// serverSentEventConnection.getWriteQueue().add(WriteQueueElement.withComment(""));

		// Get a handle to the event source (it will be created if necessary)
		DefaultServerSentEventBroadcaster broadcaster = acquireBroadcasterInternal(resourcePath, resourceMethod).get();

		if (!broadcaster.registerServerSentEventConnection(serverSentEventConnection))
			return Optional.empty();

		// Also register the connection globally so we can enforce an overall limit on the number of open connections.
		// If this causes an eviction, the eviction callback supplied to the ConcurrentLruMap will handle cleanup.
		getGlobalConnections().put(serverSentEventConnection, broadcaster);

		return Optional.of(new ClientSocketChannelRegistration(serverSentEventConnection, broadcaster));
	}

	@ThreadSafe
	protected static class DefaultServerSentEventUnicaster implements ServerSentEventUnicaster {
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final BlockingQueue<WriteQueueElement> writeQueue;

		public DefaultServerSentEventUnicaster(@Nonnull ResourcePath resourcePath,
																					 @Nonnull BlockingQueue<WriteQueueElement> writeQueue) {
			requireNonNull(resourcePath);
			requireNonNull(writeQueue);

			this.resourcePath = resourcePath;
			this.writeQueue = writeQueue;
		}

		@Override
		public void unicastEvent(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);
			getWriteQueue().add(WriteQueueElement.withServerSentEvent(serverSentEvent));
		}

		@Override
		public void unicastComment(@Nonnull String comment) {
			requireNonNull(comment);
			getWriteQueue().add(WriteQueueElement.withComment(comment));
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		protected BlockingQueue<WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}
	}

	protected void maybeCleanupBroadcaster(@Nonnull DefaultServerSentEventBroadcaster broadcaster) {
		requireNonNull(broadcaster);

		try {
			getBroadcastersByResourcePath().computeIfPresent(broadcaster.getResourcePath(), (path, existingBroadcaster) -> {
						// Only remove if it's the same instance AND empty
						if (existingBroadcaster == broadcaster && existingBroadcaster.getServerSentEventConnections().isEmpty())
							return null;  // Remove the mapping

						return existingBroadcaster; // Keep the mapping
					}
			);
		} catch (Exception e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Failed to clean up empty broadcaster")
					.throwable(e)
					.build());
		}
	}

	@Nonnull
	protected Request parseRequest(@Nonnull String requestIdentifier,
																 @Nonnull String rawRequest) throws URISyntaxException {
		requireNonNull(requestIdentifier);
		requireNonNull(rawRequest);

		rawRequest = Utilities.trimAggressivelyToNull(rawRequest);

		if (rawRequest == null)
			throw new IllegalStateException("Server-Sent Event HTTP request has no data");

		// Example request structure:
		//
		// GET /testing?one=two HTTP/1.1
		// Host: localhost:8081
		// Connection: keep-alive
		// sec-ch-ua-platform: "macOS"
		// Cache-Control: no-cache
		// User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36
		// Accept: text/event-stream
		// sec-ch-ua: "Chromium";v="130", "Google Chrome";v="130", "Not?A_Brand";v="99"
		// sec-ch-ua-mobile: ?0
		// Origin: null
		// Sec-Fetch-Site: cross-site
		// Sec-Fetch-Mode: cors
		// Sec-Fetch-Dest: empty
		// Accept-Encoding: gzip, deflate, br, zstd
		// Accept-Language: en-US,en;q=0.9,fr-CA;q=0.8,fr;q=0.7

		// We know any EventSource request must be a GET.  As a result, we know there is no request body.

		// First line is the URL and the rest are headers.
		// Line 1: GET /testing?one=two HTTP/1.1
		// Line 2: Accept-Encoding: gzip, deflate, br, zstd
		// ...and so forth.

		Request.Builder requestBuilder = null;
		List<String> headerLines = new ArrayList<>();

		for (String line : rawRequest.lines().toList()) {
			line = Utilities.trimAggressivelyToNull(line);

			if (line == null)
				continue;

			if (requestBuilder == null) {
				// This is the first line.
				// Example: GET /testing?one=two HTTP/1.1
				String[] components = line.trim().split("\\s+");

				if (components.length != 3)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				String rawHttpMethod = components[0];
				String rawUri = components[1];
				HttpMethod httpMethod = null;
				URI uri = null;

				if (rawUri != null) {
					try {
						uri = new URI(rawUri);
					} catch (Exception ignored) {
						// Malformed URI
					}
				}

				try {
					httpMethod = HttpMethod.valueOf(rawHttpMethod);
				} catch (IllegalArgumentException e) {
					// Malformed HTTP method
				}

				if (uri == null || httpMethod == null)
					throw new URISyntaxException(rawUri, format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));

				// Safely handle both absolute and relative URIs
				String finalUri = rawUri;

				try {
					URI u = new URI(rawUri);
					if (u.isAbsolute()) {
						String path = (u.getRawPath() == null ? "/" : u.getRawPath());
						String q = u.getRawQuery();
						finalUri = (q == null ? path : path + "?" + q);
					}
				} catch (Exception e) {
					throw new URISyntaxException(rawUri, format("Malformed Server-Sent Event request line '%s'. Expected a format like 'GET /example?one=two HTTP/1.1'", line));
				}

				requestBuilder = Request.with(httpMethod, finalUri);
			} else {
				if (line.isEmpty())
					continue;

				// This is a header line.
				// Example: Accept-Encoding: gzip, deflate, br, zstd
				int indexOfFirstColon = line.indexOf(":");

				if (indexOfFirstColon == -1)
					throw new IllegalStateException(format("Malformed Server-Sent Event request line '%s'. Expected a format like 'Header-Name: Value", line));

				headerLines.add(line);
			}
		}

		Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(headerLines);

		return requestBuilder.id(requestIdentifier).headers(headers).build();
	}

	@Nonnull
	protected String readRequest(@Nonnull String requestIdentifier,
															 @Nonnull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(requestIdentifier);
		requireNonNull(clientSocketChannel);

		// Because reads from the socket channel are blocking, there is no way to specify a timeout for it.
		// We work around this by performing the read in a virtual thread, and use the timeout functionality
		// built in to Futures to interrupt the thread if it doesn't finish in time.
		Future<String> readFuture = null;

		try {
			ExecutorService requestReaderExecutorService = getRequestReaderExecutorService().orElse(null);

			if (requestReaderExecutorService == null || requestReaderExecutorService.isShutdown())
				throw new IOException("Server is shutting down");

			readFuture = requestReaderExecutorService.submit(() -> {
				ByteBuffer buffer = ByteBuffer.allocate(getRequestReadBufferSizeInBytes());
				StringBuilder requestBuilder = new StringBuilder();
				boolean headersComplete = false;
				int totalBytesRead = 0;

				while (!headersComplete) {
					int bytesRead = clientSocketChannel.read(buffer);

					// If the thread was interrupted while blocked in read(...),
					// the read call should throw InterruptedIOException or similar.
					if (Thread.interrupted())
						throw new InterruptedIOException("Thread interrupted while reading request data");

					// End of stream (connection closed by client)
					if (bytesRead == -1)
						throw new IOException("Client closed the connection before request was complete");

					// Flip the buffer to read mode
					buffer.flip();

					// Decode the buffer content to a string and append to the request
					byte[] bytes = new byte[buffer.remaining()];
					buffer.get(bytes);

					totalBytesRead += bytes.length;

					// Check size limit
					// To test:
					// echo -ne 'GET /example HTTP/1.1\r\nHost: example.com FILLER_UNTIL_WE_ARE_TOO_BIG\r\n\r\n' | netcat -v localhost 8081
					if (totalBytesRead > getMaximumRequestSizeInBytes()) {
						String rawRequest = requestBuilder.toString();

						// Given our partial raw request, try to parse it into a request...
						Request tooLargeRequest = parseTooLargeRequestForRawRequest(requestIdentifier, rawRequest).orElse(null);

						// ...if unable to parse into a request (as in, we can't even make it through the first line), bail
						if (tooLargeRequest == null)
							throw new IOException(format("Request is too large (exceeded %d bytes) but we do not have enough data available to know its path", getMaximumRequestSizeInBytes()));

						throw new RequestTooLargeIOException(format("Request too large (exceeded %d bytes)", getMaximumRequestSizeInBytes()), tooLargeRequest);
					}

					requestBuilder.append(new String(bytes, StandardCharsets.UTF_8));

					// Check if the headers are complete (CRLFCRLF or LFLF)
					if (requestBuilder.indexOf("\r\n\r\n") != -1 || requestBuilder.indexOf("\n\n") != -1)
						headersComplete = true;

					// Clear the buffer for the next read
					buffer.clear();
				}

				return requestBuilder.toString();
			});

			// Wait up to the specified timeout for reading to complete
			return readFuture.get(getRequestTimeout().getSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			// Time's up; cancel the task so the blocking read is interrupted
			if (readFuture != null)
				readFuture.cancel(true);

			throw new SocketTimeoutException(format("Reading request took longer than %d seconds", getRequestTimeout().getSeconds()));
		} catch (InterruptedException e) {
			// Current thread interrupted while waiting
			Thread.currentThread().interrupt(); // restore interrupt status
			throw new IOException("Interrupted while awaiting request data", e);
		} catch (ExecutionException e) {
			// The task itself threw an exception
			if (e.getCause() instanceof IOException)
				throw (IOException) e.getCause();

			throw new IOException("Unexpected exception while reading request", e.getCause());
		}
	}

	/**
	 * Given partial raw request data (once we hit max size threshold, we stop collecting it), parse out what we have as
	 * best we can into a request that is marked "too large".
	 * <p>
	 * If there isn't sufficient data to parse into a request (or if the data is malformed), then return the empty value.
	 */
	@Nonnull
	protected Optional<Request> parseTooLargeRequestForRawRequest(@Nonnull String requestIdentifier,
																																@Nonnull String rawRequest) {
		requireNonNull(requestIdentifier);
		requireNonNull(rawRequest);

		// Supports both relative and absolute paths.
		// e.g. "GET /index.html HTTP/1.1\r\n" would return "/index.html".
		// e.g. "GET https://www.soklet.com/index.html HTTP/1.1\r\n" would return "/index.html".
		String firstLine = null;

		int crLfIndex = rawRequest.indexOf("\r\n");
		if (crLfIndex == -1)
			crLfIndex = rawRequest.indexOf('\n');

		if (crLfIndex != -1)
			firstLine = rawRequest.substring(0, crLfIndex).trim();

		// We don't even have a complete first line of the request
		if (firstLine == null || firstLine.length() == 0)
			return Optional.empty();

		String[] parts = firstLine.trim().split("\\s+");

		// First line of the request is malformed
		if (parts.length < 2)
			return Optional.empty();

		String rawHttpMethod = parts[0];

		if (rawHttpMethod != null)
			rawHttpMethod = rawHttpMethod.trim().toUpperCase(Locale.ENGLISH);

		HttpMethod httpMethod = null;

		try {
			httpMethod = HttpMethod.valueOf(rawHttpMethod);
		} catch (IllegalArgumentException e) {
			// Malformed HTTP method specified
			return Optional.empty();
		}

		String rawUri = parts[1];

		// Validate URI
		if (rawUri != null) {
			try {
				new URI(rawUri.trim());
			} catch (Exception e) {
				// Malformed URI specified
				return Optional.empty();
			}
		}

		// TODO: eventually would be nice to parse headers as best we can.  For now, we just parse the first request line
		return Optional.of(Request.with(httpMethod, rawUri)
				.id(requestIdentifier)
				.contentTooLarge(true)
				.build());
	}

	@NotThreadSafe
	protected static class RequestTooLargeIOException extends IOException {
		@Nonnull
		private final Request tooLargeRequest;

		public RequestTooLargeIOException(@Nullable String message,
																			@Nonnull Request tooLargeRequest) {
			super(message);
			this.tooLargeRequest = requireNonNull(tooLargeRequest);
		}

		@Nonnull
		public Request getTooLargeRequest() {
			return this.tooLargeRequest;
		}
	}

	@Override
	public void stop() {
		getLock().lock();

		boolean interrupted = false;

		try {
			if (!isStarted())
				return;

			this.stopping = true;
			getStopPoisonPill().set(true);

			// Close server socket to unblock accept()
			if (this.serverSocketChannel != null) {
				try {
					this.serverSocketChannel.close();
				} catch (Exception e) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
							"Unable to close Server-Sent Event SocketChannel").throwable(e).build());
				}
			}

			// Close client connections
			for (DefaultServerSentEventBroadcaster broadcaster : getBroadcastersByResourcePath().values()) {
				try {
					broadcaster.unregisterAllServerSentEventConnections(true);
				} catch (Exception e) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to shut down open Server-Sent Event connections")
							.throwable(e)
							.build());
				}
			}

			// Clear global connections map for sanity (though it should be empty by this point)
			getGlobalConnections().clear();

			// Initiate shutdowns quickly (no blocking here beyond close() above)
			if (this.connectionValidityExecutorService != null)
				this.connectionValidityExecutorService.shutdownNow();

			if (this.requestHandlerExecutorService != null)
				this.requestHandlerExecutorService.shutdown();

			if (this.requestReaderExecutorService != null)
				this.requestReaderExecutorService.shutdown();

			// Shared wall-clock deadline
			final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();

			// Await the accept-loop thread and all executors **in parallel**
			// Give a short grace period to the remaining components
			long grace = Math.min(250L, remainingMillis(deadlineNanos));

			List<CompletableFuture<Boolean>> waits = new ArrayList<>(3);
			waits.add(joinAsync(this.eventLoopThread, grace));

			if (this.requestHandlerExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestHandlerExecutorService, grace));

			if (this.requestReaderExecutorService != null)
				waits.add(awaitTerminationAsync(this.requestReaderExecutorService, grace));

			// Wait for all, but no longer than the single budget
			try {
				CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).get(grace, TimeUnit.MILLISECONDS);
			} catch (TimeoutException te) {
				// Budget exhausted; escalate below
			} catch (InterruptedException e) {
				interrupted = true;
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				// Log and continue shutdown
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Exception while awaiting SSE shutdown").throwable(e).build());
			}

			// Escalate for any stragglers using remaining time
			hardenJoin(this.eventLoopThread, remainingMillis(deadlineNanos));

			if (this.requestHandlerExecutorService != null)
				hardenPool(this.requestHandlerExecutorService, remainingMillis(deadlineNanos));

			if (this.requestReaderExecutorService != null)
				hardenPool(this.requestReaderExecutorService, remainingMillis(deadlineNanos));
		} finally {
			try {
				this.started = false;
				this.stopping = false; // allow future restarts
				this.eventLoopThread = null;
				this.requestHandlerExecutorService = null;
				this.requestReaderExecutorService = null;
				this.connectionValidityExecutorService = null;
				this.getBroadcastersByResourcePath().clear();
				this.getResourcePathDeclarationsByResourcePathCache().clear();
				getStopPoisonPill().set(false);

				if (interrupted)
					Thread.currentThread().interrupt();
			} finally {
				getLock().unlock();
			}
		}
	}

	@Nonnull
	protected CompletableFuture<Boolean> awaitTerminationAsync(@Nullable ExecutorService executorService,
																														 @Nonnull Long millis) {
		requireNonNull(millis);

		if (executorService == null || millis <= 0)
			return CompletableFuture.completedFuture(false);

		return CompletableFuture.supplyAsync(() -> {
			try {
				return executorService.awaitTermination(millis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	@Nonnull
	protected CompletableFuture<Boolean> joinAsync(@Nullable Thread thread,
																								 @Nonnull Long millis) {
		requireNonNull(millis);

		if (thread == null || millis <= 0)
			return CompletableFuture.completedFuture(true);

		return CompletableFuture.supplyAsync(() -> {
			try {
				thread.join(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}

			return !thread.isAlive();
		});
	}

	protected void hardenPool(@Nullable ExecutorService executorService,
														@Nonnull Long millis) {
		requireNonNull(millis);

		if (executorService == null || executorService.isTerminated())
			return;

		// Interrupt stuck tasks
		executorService.shutdownNow();

		try {
			executorService.awaitTermination(Math.max(100L, millis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	protected void hardenJoin(@Nullable Thread thread,
														@Nonnull Long millis) {
		requireNonNull(millis);

		if (thread == null || !thread.isAlive())
			return;

		thread.interrupt();

		try {
			thread.join(Math.max(100L, millis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Nonnull
	protected Long remainingMillis(@Nonnull Long deadlineNanos) {
		requireNonNull(deadlineNanos);

		long rem = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
		return Math.max(0L, rem);
	}

	@Nonnull
	@Override
	public Boolean isStarted() {
		getLock().lock();

		try {
			return this.started;
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	protected Boolean isStopping() {
		getLock().lock();

		try {
			return this.stopping;
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	@Override
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		return acquireBroadcasterInternal(resourcePath, resourceMethod);
	}

	@Nonnull
	protected Optional<ResourceMethod> resourceMethodForResourcePath(@Nonnull ResourcePath resourcePath) {
		requireNonNull(resourcePath);

		ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(resourcePath).orElse(null);

		if (resourcePathDeclaration == null)
			return Optional.empty();

		ResourceMethod resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

		// Internal sanity guard
		if (resourceMethod == null)
			throw new IllegalStateException(format("Internal error: unable to find %s instance that matches %s", ResourceMethod.class, resourcePathDeclaration));

		return Optional.of(resourceMethod);
	}

	@Nonnull
	protected Optional<DefaultServerSentEventBroadcaster> acquireBroadcasterInternal(@Nonnull ResourcePath resourcePath,
																																									 @Nonnull ResourceMethod resourceMethod) {
		requireNonNull(resourcePath);
		requireNonNull(resourceMethod);

		// Create the event source if it does not already exist
		DefaultServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
				.computeIfAbsent(resourcePath, (ignored) -> new DefaultServerSentEventBroadcaster(resourceMethod, resourcePath, (serverSentEventConnection -> {
					// When the broadcaster unregisters a connection it manages, remove it from the global set of connections as well
					getGlobalConnections().remove(serverSentEventConnection);
				})));

		return Optional.of(broadcaster);
	}

	@Nonnull
	protected Optional<ResourcePathDeclaration> matchingResourcePath(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		ResourcePathDeclaration resourcePathDeclaration = getResourcePathDeclarationsByResourcePathCache()
				.computeIfAbsent(resourcePath, rp -> {
					for (ResourcePathDeclaration d : getResourceMethodsByResourcePathDeclaration().keySet())
						if (d.matches(rp))
							return d;

					return null;
				});

		return Optional.ofNullable(resourcePathDeclaration);
	}

	protected void safelyLog(@Nonnull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getLifecycleInterceptor().ifPresent(lifecycleInterceptor -> lifecycleInterceptor.didReceiveLogEvent(logEvent));
		} catch (Throwable throwable) {
			// The LifecycleInterceptor implementation errored out, but we can't let that affect us - swallow its exception.
			// Not much else we can do here but dump to stderr
			throwable.printStackTrace();
		}
	}

	@Nonnull
	protected Integer getPort() {
		return this.port;
	}

	@Nonnull
	protected String getHost() {
		return this.host;
	}

	@Nonnull
	protected Duration getRequestTimeout() {
		return this.requestTimeout;
	}

	@Nonnull
	protected Duration getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	@Nonnull
	protected Duration getHeartbeatInterval() {
		return this.heartbeatInterval;
	}

	@Nonnull
	protected AtomicInteger getHeartbeatBatch() {
		return this.heartbeatBatch;
	}

	@Nonnull
	protected Integer getMaximumRequestSizeInBytes() {
		return this.maximumRequestSizeInBytes;
	}

	@Nonnull
	protected Integer getRequestReadBufferSizeInBytes() {
		return this.requestReadBufferSizeInBytes;
	}

	@Nonnull
	public Map<ResourcePathDeclaration, ResourceMethod> getResourceMethodsByResourcePathDeclaration() {
		return this.resourceMethodsByResourcePathDeclaration;
	}

	@Nonnull
	protected ConcurrentLruMap<ResourcePath, DefaultServerSentEventBroadcaster> getBroadcastersByResourcePath() {
		return this.broadcastersByResourcePath;
	}

	@Nonnull
	protected ConcurrentLruMap<ResourcePath, ResourcePathDeclaration> getResourcePathDeclarationsByResourcePathCache() {
		return this.resourcePathDeclarationsByResourcePathCache;
	}

	@Nonnull
	protected Optional<ExecutorService> getRequestHandlerExecutorService() {
		return Optional.ofNullable(this.requestHandlerExecutorService);
	}

	@Nonnull
	protected Optional<ExecutorService> getRequestReaderExecutorService() {
		return Optional.ofNullable(this.requestReaderExecutorService);
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@Nonnull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@Nonnull
	protected Supplier<ExecutorService> getRequestReaderExecutorServiceSupplier() {
		return this.requestReaderExecutorServiceSupplier;
	}

	@Nonnull
	protected Integer getConcurrentConnectionLimit() {
		return this.concurrentConnectionLimit;
	}

	@Nonnull
	protected Integer getConnectionQueueCapacity() {
		return this.connectionQueueCapacity;
	}

	@Nonnull
	protected ConcurrentLruMap<ServerSentEventConnection, DefaultServerSentEventBroadcaster> getGlobalConnections() {
		return this.globalConnections;
	}

	@Nullable
	protected ScheduledExecutorService getConnectionValidityExecutorService() {
		return this.connectionValidityExecutorService;
	}

	@Nonnull
	protected AtomicBoolean getStopPoisonPill() {
		return this.stopPoisonPill;
	}

	@Nonnull
	protected Optional<Thread> getEventLoopThread() {
		return Optional.ofNullable(this.eventLoopThread);
	}

	@Nonnull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	@Nonnull
	protected Optional<LifecycleInterceptor> getLifecycleInterceptor() {
		return Optional.ofNullable(this.lifecycleInterceptor);
	}
}
