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

import com.soklet.annotation.ServerSentEventSource;
import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.util.ConcurrentLruMap;
import com.soklet.internal.util.HostHeaderValidator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultServerSentEventServer implements ServerSentEventServer {
	@NonNull
	private static final String DEFAULT_HOST;
	@NonNull
	private static final Duration DEFAULT_REQUEST_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_REQUEST_HANDLER_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_WRITE_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
	@NonNull
	private static final Integer DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
	@NonNull
	private static final Duration DEFAULT_HEARTBEAT_INTERVAL;
	@NonNull
	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT;
	@NonNull
	private static final Integer DEFAULT_CONNECTION_QUEUE_CAPACITY;
	@NonNull
	private static final Integer DEFAULT_CONCURRENT_CONNECTION_LIMIT;
	@NonNull
	private static final Integer DEFAULT_BROADCASTER_CACHE_CAPACITY;
	@NonNull
	private static final Integer DEFAULT_RESOURCE_PATH_CACHE_CAPACITY;
	@NonNull
	private static final Boolean DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED;
	@NonNull
	private static final byte[] FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;
	@NonNull
	private static final byte[] FAILSAFE_HANDSHAKE_HTTP_503_RESPONSE;
	@NonNull
	private static final byte[] FAILSAFE_HANDSHAKE_HTTP_400_RESPONSE;
	@NonNull
	private static final ResourcePathDeclaration NO_MATCH_SENTINEL;
	@NonNull
	private static final String HEARTBEAT_COMMENT_PAYLOAD;
	@NonNull
	private static final byte[] HEARTBEAT_COMMENT_PAYLOAD_BYTES;
	@NonNull
	private static final ServerSentEventComment HEARTBEAT_COMMENT;
	@NonNull
	private static final Integer DEFAULT_SSE_BUILDER_CAPACITY;

	static {
		DEFAULT_HOST = "0.0.0.0";
		DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_REQUEST_HANDLER_TIMEOUT = Duration.ofSeconds(60);
		DEFAULT_WRITE_TIMEOUT = Duration.ZERO;
		DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES = 64 * 1_024;
		DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES = 1_024;
		DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
		DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);
		DEFAULT_CONNECTION_QUEUE_CAPACITY = 512;
		DEFAULT_CONCURRENT_CONNECTION_LIMIT = 8_192;
		DEFAULT_BROADCASTER_CACHE_CAPACITY = 1_024;
		DEFAULT_RESOURCE_PATH_CACHE_CAPACITY = 8_192;
		DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED = true;

		// Cache off a special failsafe response
		FAILSAFE_HANDSHAKE_HTTP_400_RESPONSE = createFailsafeHandshakeHttpResponse(StatusCode.HTTP_400);
		FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE = createFailsafeHandshakeHttpResponse(StatusCode.HTTP_500);
		FAILSAFE_HANDSHAKE_HTTP_503_RESPONSE = createFailsafeHandshakeHttpResponse(StatusCode.HTTP_503);

		// Create a sentinel ResourcePathDeclaration that represents "no match found".
		// This allows us to cache negative lookups and avoid repeated O(n) scans.
		NO_MATCH_SENTINEL = ResourcePathDeclaration.withPath("/__soklet_internal_no_match_sentinel__");

		HEARTBEAT_COMMENT_PAYLOAD = ":\n\n";
		HEARTBEAT_COMMENT_PAYLOAD_BYTES = HEARTBEAT_COMMENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
		HEARTBEAT_COMMENT = ServerSentEventComment.withHeartbeat().build();
		DEFAULT_SSE_BUILDER_CAPACITY = 256;
	}

	/**
	 * Strategy/callback for handling backpressure on a single connection's write queue.
	 * Implemented by the server; injected into broadcasters. Avoids a broadcaster holding a server reference explicitly.
	 */
	@FunctionalInterface
	private interface BackpressureHandler {
		void onBackpressure(@NonNull DefaultServerSentEventBroadcaster owner,
												@NonNull DefaultServerSentEventConnection connection,
												@NonNull String cause);
	}

	@NonNull
	private final Integer port;
	@NonNull
	private final String host;
	@NonNull
	private final Duration requestTimeout;
	@NonNull
	private final Duration requestHandlerTimeout;
	@NonNull
	private final Duration writeTimeout;
	@NonNull
	private final Duration shutdownTimeout;
	@NonNull
	private final Duration heartbeatInterval;
	@NonNull
	private final Integer maximumRequestSizeInBytes;
	@NonNull
	private final Integer requestReadBufferSizeInBytes;
	@NonNull
	private final Boolean verifyConnectionOnceEstablished;
	@NonNull
	private final ConcurrentHashMap<@NonNull ResourcePath, @NonNull DefaultServerSentEventBroadcaster> broadcastersByResourcePath;
	@NonNull
	private final ConcurrentLruMap<@NonNull ResourcePath, @NonNull ResourcePathDeclaration> resourcePathDeclarationsByResourcePathCache;
	@NonNull
	private final ConcurrentHashMap<@NonNull DefaultServerSentEventConnection, @NonNull DefaultServerSentEventBroadcaster> globalConnections;
	@NonNull
	private final AtomicInteger activeConnectionCount;
	@NonNull
	private final ConcurrentLruMap<@NonNull ResourcePath, @NonNull DefaultServerSentEventBroadcaster> idleBroadcastersByResourcePath;
	@NonNull
	private final ReentrantLock lock;
	@NonNull
	private final Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
	@NonNull
	private final Supplier<ExecutorService> requestReaderExecutorServiceSupplier;
	@NonNull
	private final Integer concurrentConnectionLimit;
	@NonNull
	private final Integer connectionQueueCapacity;
	@NonNull
	private final AtomicBoolean stopPoisonPill;
	@Nullable
	private volatile ExecutorService requestHandlerExecutorService;
	@Nullable
	private volatile ScheduledExecutorService requestHandlerTimeoutExecutorService;
	@Nullable
	private volatile ExecutorService requestReaderExecutorService;
	@NonNull
	private volatile Boolean started;
	@NonNull
	private volatile Boolean stopping;
	@Nullable
	private Thread eventLoopThread;
	@Nullable
	private volatile ServerSocketChannel serverSocketChannel;
	// Does not need to be concurrent because it's calculated just once at initialization time and is never modified after
	@NonNull
	private volatile Map<@NonNull ResourcePathDeclaration, @NonNull ResourceMethod> resourceMethodsByResourcePathDeclaration;
	@Nullable
	private RequestHandler requestHandler;
	@Nullable
	private LifecycleObserver lifecycleObserver;
	@Nullable
	private MetricsCollector metricsCollector;
	@Nullable
	private ResponseMarshaler responseMarshaler;
	@Nullable
	private IdGenerator<?> idGenerator;

	@ThreadSafe
	private static class DefaultServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@NonNull
		private final ResourceMethod resourceMethod;
		@NonNull
		private final ResourcePath resourcePath;
		@NonNull
		private final BackpressureHandler backpressureHandler;
		@NonNull
		private final Consumer<DefaultServerSentEventConnection> connectionUnregisteredListener;
		@NonNull
		private final Consumer<LogEvent> logEventConsumer;
		// This must be threadsafe, e.g. via ConcurrentHashMap#newKeySet
		@NonNull
		private final Set<@NonNull DefaultServerSentEventConnection> serverSentEventConnections;

		public DefaultServerSentEventBroadcaster(@NonNull ResourceMethod resourceMethod,
																						 @NonNull ResourcePath resourcePath,
																						 @NonNull BackpressureHandler backpressureHandler,
																						 @NonNull Consumer<DefaultServerSentEventConnection> connectionUnregisteredListener,
																						 @NonNull Consumer<LogEvent> logEventConsumer) {
			requireNonNull(resourceMethod);
			requireNonNull(resourcePath);
			requireNonNull(backpressureHandler);
			requireNonNull(connectionUnregisteredListener);
			requireNonNull(logEventConsumer);

			this.resourceMethod = resourceMethod;
			this.resourcePath = resourcePath;
			this.backpressureHandler = backpressureHandler;
			this.connectionUnregisteredListener = connectionUnregisteredListener;
			this.logEventConsumer = logEventConsumer;
			// TODO: let clients specify capacity
			this.serverSentEventConnections = ConcurrentHashMap.newKeySet(256);
		}

		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@NonNull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		@Override
		public Long getClientCount() {
			return (long) getServerSentEventConnections().size();
		}

		@Override
		public void broadcastEvent(@NonNull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			DefaultServerSentEventConnection.PreSerializedEvent preSerializedEvent = preSerializeServerSentEvent(serverSentEvent);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (DefaultServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueuePreSerializedEvent(this, serverSentEventConnection, preSerializedEvent, getBackpressureHandler());
		}

		@Override
		public void broadcastComment(@NonNull ServerSentEventComment serverSentEventComment) {
			requireNonNull(serverSentEventComment);

			// We can broadcast from the current thread because putting elements onto blocking queues is reasonably fast.
			// The blocking queues are consumed by separate per-socket-channel threads
			for (DefaultServerSentEventConnection serverSentEventConnection : getServerSentEventConnections())
				enqueueComment(this, serverSentEventConnection, serverSentEventComment, getBackpressureHandler());
		}

		@Override
		public <T> void broadcastEvent(@NonNull Function<Object, T> keySelector,
																	 @NonNull Function<T, ServerSentEvent> eventProvider) {
			requireNonNull(keySelector);
			requireNonNull(eventProvider);

			Map<T, DefaultServerSentEventConnection.PreSerializedEvent> payloadCache = new HashMap<>();

			for (DefaultServerSentEventConnection connection : getServerSentEventConnections()) {
				Object clientContext = connection.getClientContext().orElse(null);

				try {
					// Ask client code to generate a key given the context object
					T key = keySelector.apply(clientContext);

					// Ask client code to generate payload (if not present in our local cache)
					DefaultServerSentEventConnection.PreSerializedEvent preSerializedEvent =
							payloadCache.computeIfAbsent(key, (cacheKey) -> preSerializeServerSentEvent(eventProvider.apply(cacheKey)));
					enqueuePreSerializedEvent(this, connection, preSerializedEvent, getBackpressureHandler());
				} catch (Throwable t) {
					this.getLogEventConsumer().accept(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_BROADCAST_GENERATION_FAILED,
									format("Failed to generate Server-Sent-Event for connection on %s with client context %s",
											getResourcePath(), (clientContext == null ? "[none specified]" : clientContext)))
							.throwable(t)
							.build());
				}
			}
		}

		@Override
		public <T> void broadcastComment(@NonNull Function<Object, T> keySelector,
																		 @NonNull Function<T, ServerSentEventComment> commentProvider) {
			requireNonNull(keySelector);
			requireNonNull(commentProvider);

			Map<T, ServerSentEventComment> payloadCache = new HashMap<>();

			for (DefaultServerSentEventConnection connection : getServerSentEventConnections()) {
				Object clientContext = connection.getClientContext().orElse(null);

				try {
					// Ask client code to generate a key given the context object
					T key = keySelector.apply(clientContext);

					// Ask client code to generate payload (if not present in our local cache)
					ServerSentEventComment comment = payloadCache.computeIfAbsent(key, commentProvider);

					enqueueComment(this, connection, comment, getBackpressureHandler());
				} catch (Throwable t) {
					this.getLogEventConsumer().accept(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_BROADCAST_GENERATION_FAILED,
									format("Failed to generate Server-Sent Event comment for connection on %s with client context %s",
											getResourcePath(), (clientContext == null ? "[none specified]" : clientContext)))
							.throwable(t)
							.build());
				}
			}
		}

		@NonNull
		public Boolean registerServerSentEventConnection(@Nullable DefaultServerSentEventConnection serverSentEventConnection) {
			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			return getServerSentEventConnections().add(serverSentEventConnection);
		}

		@NonNull
		public Boolean unregisterServerSentEventConnection(@Nullable DefaultServerSentEventConnection serverSentEventConnection,
																											 @NonNull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			if (serverSentEventConnection == null)
				return false;

			// Underlying set is threadsafe so this is OK
			boolean unregistered = getServerSentEventConnections().remove(serverSentEventConnection);

			if (unregistered)
				getConnectionUnregisteredListener().accept(serverSentEventConnection);

			// Send poison pill regardless of whether we were registered, if requested.
			// This handles the edge case where eviction happens before registration completes.
			if (sendPoisonPill)
				enqueuePoisonPill(this, serverSentEventConnection, getBackpressureHandler());

			return unregistered;
		}

		public void unregisterAllServerSentEventConnections(@NonNull Boolean sendPoisonPill) {
			requireNonNull(sendPoisonPill);

			// Snapshot list for consistency during unregister process
			for (DefaultServerSentEventConnection serverSentEventConnection : new ArrayList<>(getServerSentEventConnections()))
				unregisterServerSentEventConnection(serverSentEventConnection, sendPoisonPill);
		}

		@NonNull
		private BackpressureHandler getBackpressureHandler() {
			return this.backpressureHandler;
		}

		@NonNull
		private Set<@NonNull DefaultServerSentEventConnection> getServerSentEventConnections() {
			return this.serverSentEventConnections;
		}

		@NonNull
		private Consumer<DefaultServerSentEventConnection> getConnectionUnregisteredListener() {
			return this.connectionUnregisteredListener;
		}

		@NonNull
		private Consumer<LogEvent> getLogEventConsumer() {
			return this.logEventConsumer;
		}
	}

	/**
	 * Instead of returning an actual DefaultServerSentEventBroadcaster instance to client code, we return this lightweight object that:
	 * <ul>
	 * <li>stores only ResourcePath (and optionally ResourceMethod)</li>
	 * <li>looks up the current broadcaster in broadcastersByResourcePath on every call</li>
	 * <li>no-ops if no broadcaster exists (i.e. no clients currently connected)</li>
	 * </ul>
	 * <p>
	 * This makes cached broadcaster handles safe forever, while keeping idle eviction working as expected.
	 */
	@ThreadSafe
	private final class StableBroadcasterHandle implements ServerSentEventBroadcaster {
		@NonNull
		private final ResourcePath resourcePath;

		private StableBroadcasterHandle(@NonNull ResourcePath resourcePath) {
			this.resourcePath = requireNonNull(resourcePath);
		}

		@Override
		@NonNull
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Override
		@NonNull
		public Long getClientCount() {
			DefaultServerSentEventBroadcaster b = getBroadcastersByResourcePath().get(resourcePath);
			return b != null ? b.getClientCount() : 0L;
		}

		@Override
		public void broadcastEvent(@NonNull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);
			DefaultServerSentEventBroadcaster b = getBroadcastersByResourcePath().get(resourcePath);
			if (b != null) b.broadcastEvent(serverSentEvent);
		}

		@Override
		public void broadcastComment(@NonNull ServerSentEventComment serverSentEventComment) {
			requireNonNull(serverSentEventComment);
			DefaultServerSentEventBroadcaster b = getBroadcastersByResourcePath().get(resourcePath);
			if (b != null) b.broadcastComment(serverSentEventComment);
		}

		@Override
		public <T> void broadcastEvent(@NonNull Function<Object, T> keySelector,
																	 @NonNull Function<T, ServerSentEvent> eventProvider) {
			requireNonNull(keySelector);
			requireNonNull(eventProvider);
			DefaultServerSentEventBroadcaster b = getBroadcastersByResourcePath().get(resourcePath);
			if (b != null) b.broadcastEvent(keySelector, eventProvider);
		}

		@Override
		public <T> void broadcastComment(@NonNull Function<Object, T> keySelector,
																		 @NonNull Function<T, ServerSentEventComment> commentProvider) {
			requireNonNull(keySelector);
			requireNonNull(commentProvider);
			DefaultServerSentEventBroadcaster b = getBroadcastersByResourcePath().get(resourcePath);
			if (b != null) b.broadcastComment(keySelector, commentProvider);
		}
	}

	DefaultServerSentEventServer(@NonNull Builder builder) {
		requireNonNull(builder);

		this.stopPoisonPill = new AtomicBoolean(false);
		this.started = false;
		this.stopping = false;
		this.lock = new ReentrantLock();
		this.port = builder.port;
		this.host = builder.host != null ? builder.host : DEFAULT_HOST;
		this.maximumRequestSizeInBytes = builder.maximumRequestSizeInBytes != null ? builder.maximumRequestSizeInBytes : DEFAULT_MAXIMUM_REQUEST_SIZE_IN_BYTES;
		this.requestReadBufferSizeInBytes = builder.requestReadBufferSizeInBytes != null ? builder.requestReadBufferSizeInBytes : DEFAULT_REQUEST_READ_BUFFER_SIZE_IN_BYTES;
		this.verifyConnectionOnceEstablished = builder.verifyConnectionOnceEstablished != null ? builder.verifyConnectionOnceEstablished : DEFAULT_VERIFY_CONNECTION_ONCE_ESTABLISHED;
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.withDefaults();
		this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
		this.requestHandlerTimeout = builder.requestHandlerTimeout != null ? builder.requestHandlerTimeout : DEFAULT_REQUEST_HANDLER_TIMEOUT;
		this.writeTimeout = builder.writeTimeout != null ? builder.writeTimeout : DEFAULT_WRITE_TIMEOUT;
		this.shutdownTimeout = builder.shutdownTimeout != null ? builder.shutdownTimeout : DEFAULT_SHUTDOWN_TIMEOUT;
		this.heartbeatInterval = builder.heartbeatInterval != null ? builder.heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
		this.resourceMethodsByResourcePathDeclaration = Map.of(); // Temporary to remain non-null; will be overridden by Soklet via #initialize

		if (this.maximumRequestSizeInBytes <= 0)
			throw new IllegalArgumentException("Maximum request size must be > 0");

		if (this.requestReadBufferSizeInBytes <= 0)
			throw new IllegalArgumentException("Request read buffer size must be > 0");

		if (this.requestTimeout.isNegative() || this.requestTimeout.isZero())
			throw new IllegalArgumentException("Request timeout must be > 0");

		if (this.requestHandlerTimeout.isNegative() || this.requestHandlerTimeout.isZero())
			throw new IllegalArgumentException("Request handler timeout must be > 0");

		if (this.writeTimeout.isNegative())
			throw new IllegalArgumentException("Write timeout must be >= 0");

		if (this.shutdownTimeout.isNegative())
			throw new IllegalArgumentException("Shutdown timeout must be >= 0");

		if (this.heartbeatInterval.isNegative() || this.heartbeatInterval.isZero())
			throw new IllegalArgumentException("Heartbeat interval must be > 0");

		int broadcasterInitialCapacity = builder.broadcasterCacheCapacity != null
				? builder.broadcasterCacheCapacity
				: DEFAULT_BROADCASTER_CACHE_CAPACITY;

		// Important: broadcaster registry must not evict live broadcasters, or active clients can become orphaned.
		// We rely on maybeCleanupBroadcaster(...) to remove empty broadcasters safely.
		this.broadcastersByResourcePath = new ConcurrentHashMap<>(broadcasterInitialCapacity);

		this.resourcePathDeclarationsByResourcePathCache = new ConcurrentLruMap<>(builder.resourcePathCacheCapacity != null ? builder.resourcePathCacheCapacity : DEFAULT_RESOURCE_PATH_CACHE_CAPACITY, (resourcePath, broadcaster) -> { /* nothing to do for now */});

		int idleBroadcasterCacheCapacity = builder.broadcasterCacheCapacity != null
				? builder.broadcasterCacheCapacity
				: DEFAULT_BROADCASTER_CACHE_CAPACITY;

		// LRU of *idle* (zero-connection) broadcasters.
		// When an idle broadcaster is evicted here, we attempt to remove it from the main registry,
		// but only if it is still mapped and still idle. This avoids orphaning active connections.
		this.idleBroadcastersByResourcePath = new ConcurrentLruMap<>(idleBroadcasterCacheCapacity, (resourcePath, broadcaster) -> {
			try {
				this.broadcastersByResourcePath.computeIfPresent(resourcePath, (rp, existing) -> {
					if (existing == broadcaster && existing.getServerSentEventConnections().isEmpty())
						return null; // remove from main registry -> eligible for GC
					return existing;
				});
			} catch (Throwable t) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR,
								"Failed to evict idle SSE broadcaster")
						.throwable(t)
						.build());
			}
		});

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
					throwable.printStackTrace(System.err);
					loggingThrowable.printStackTrace(System.err);
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
					throwable.printStackTrace(System.err);
					loggingThrowable.printStackTrace(System.err);
				}
			});
		};

		this.concurrentConnectionLimit = builder.concurrentConnectionLimit != null ? builder.concurrentConnectionLimit : DEFAULT_CONCURRENT_CONNECTION_LIMIT;

		if (this.concurrentConnectionLimit < 1)
			throw new IllegalArgumentException("The value for concurrentConnectionLimit must be > 0");

		this.connectionQueueCapacity = builder.connectionQueueCapacity != null ? builder.connectionQueueCapacity : DEFAULT_CONNECTION_QUEUE_CAPACITY;

		if (this.connectionQueueCapacity < 1)
			throw new IllegalArgumentException("The value for connectionQueueCapacity must be > 0");

		// Initialize the global LRU map with the specified limit.
		// We do not need an eviction listener because we will not evict active connections.
		this.globalConnections = new ConcurrentHashMap<>(getConcurrentConnectionLimit());
		this.activeConnectionCount = new AtomicInteger(0);
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		requireNonNull(sokletConfig);
		requireNonNull(requestHandler);

		this.lifecycleObserver = sokletConfig.getLifecycleObserver();
		this.metricsCollector = sokletConfig.getMetricsCollector();
		this.responseMarshaler = sokletConfig.getResponseMarshaler();
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

			if (getLifecycleObserver().isEmpty())
				throw new IllegalStateException(format("No %s was registered for %s", LifecycleObserver.class, getClass()));

			this.requestHandlerExecutorService = getRequestHandlerExecutorServiceSupplier().get();
			this.requestHandlerTimeoutExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "sse-request-handler-timeout"));
			this.requestReaderExecutorService = getRequestReaderExecutorServiceSupplier().get();
			this.stopping = false;
			this.started = true; // set before thread starts to avoid early exit races
			this.eventLoopThread = new Thread(this::startInternal, "sse-event-loop");
			this.eventLoopThread.start();
		} finally {
			getLock().unlock();
		}
	}

	private static DefaultServerSentEventConnection.@NonNull PreSerializedEvent preSerializeServerSentEvent(@NonNull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEvent);

		StringBuilder stringBuilder = new StringBuilder(DEFAULT_SSE_BUILDER_CAPACITY);
		String formatted = formatServerSentEventForResponse(serverSentEvent, stringBuilder);
		byte[] payloadBytes = formatted == HEARTBEAT_COMMENT_PAYLOAD
				? HEARTBEAT_COMMENT_PAYLOAD_BYTES
				: formatted.getBytes(StandardCharsets.UTF_8);

		return new DefaultServerSentEventConnection.PreSerializedEvent(serverSentEvent, payloadBytes);
	}

	private static boolean isRemoteClose(@Nullable Throwable throwable) {
		if (throwable == null)
			return false;

		Throwable current = throwable;

		while (current != null) {
			if (current instanceof ClosedChannelException)
				return true;
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
							|| normalized.contains("software caused connection abort"))
						return true;
				}
			}

			current = current.getCause();
		}

		return false;
	}

	@NonNull
	private static Boolean enqueuePreSerializedEvent(@NonNull DefaultServerSentEventBroadcaster owner,
																									 @NonNull DefaultServerSentEventConnection connection,
																									 DefaultServerSentEventConnection.@NonNull PreSerializedEvent preSerializedEvent,
																									 @NonNull BackpressureHandler handler) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(preSerializedEvent);
		requireNonNull(handler);

		BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue = connection.getWriteQueue();
		DefaultServerSentEventConnection.WriteQueueElement e =
				DefaultServerSentEventConnection.WriteQueueElement.withPreSerializedEvent(preSerializedEvent, System.nanoTime());

		if (writeQueue.offer(e))
			return true;

		// Queue full — delegate to handler: log + close + unregister.
		handler.onBackpressure(owner, connection, "event");
		return false;
	}

	@NonNull
	private static Boolean enqueuePoisonPill(@NonNull DefaultServerSentEventBroadcaster owner,
																					 @NonNull DefaultServerSentEventConnection connection,
																					 @NonNull BackpressureHandler handler) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(handler);

		BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue = connection.getWriteQueue();

		if (writeQueue.offer(DefaultServerSentEventConnection.WriteQueueElement.poisonPill()))
			return true;

		handler.onBackpressure(owner, connection, "poison-pill");
		return false;
	}

	@NonNull
	private static Boolean enqueueComment(@NonNull DefaultServerSentEventBroadcaster owner,
																				@NonNull DefaultServerSentEventConnection connection,
																				@NonNull ServerSentEventComment serverSentEventComment,
																				@NonNull BackpressureHandler handler) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(serverSentEventComment);
		requireNonNull(handler);

		BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue = connection.getWriteQueue();
		DefaultServerSentEventConnection.WriteQueueElement writeQueueElement =
				DefaultServerSentEventConnection.WriteQueueElement.withComment(serverSentEventComment, System.nanoTime());

		if (writeQueue.offer(writeQueueElement))
			return true;

		handler.onBackpressure(owner, connection, "comment");
		return false;
	}

	protected void startInternal() {
		if (!isStarted() || isStopping())
			return;

		ServerSocketChannel serverSocketChannel;
		boolean bindSucceeded = false;

		try {
			serverSocketChannel = ServerSocketChannel.open();
			serverSocketChannel.bind(new InetSocketAddress(getHost(), getPort()));

			bindSucceeded = true;

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

			// If the socket was never bound, ensure a correct stop
			if (!bindSucceeded)
				stop();
		}
	}

	protected void handleClientSocketChannel(@NonNull SocketChannel clientSocketChannel) {
		requireNonNull(clientSocketChannel);

		ClientSocketChannelRegistration clientSocketChannelRegistration = null;
		Request request = null;
		ResourceMethod resourceMethod = null;
		Instant writeStarted;
		Throwable throwable = null;
		Object channelLock = new Object();

		// Keep track of whether we should go through the "handshake accepted" flow.
		// Might change from "accepted" to "rejected" if an error occurs
		AtomicReference<HandshakeResult.Accepted> handshakeAcceptedReference = new AtomicReference<>();
		AtomicBoolean connectionSlotReserved = new AtomicBoolean(false);
		AtomicBoolean handshakeResponseWritten = new AtomicBoolean(false);
		AtomicReference<ScheduledFuture<?>> handshakeTimeoutFutureRef = new AtomicReference<>();
		InetSocketAddress remoteAddress = null;

		try (clientSocketChannel) {
			try {
				try {
					SocketAddress socketAddress = clientSocketChannel.getRemoteAddress();
					if (socketAddress instanceof InetSocketAddress)
						remoteAddress = (InetSocketAddress) socketAddress;
				} catch (IOException ignored) {
					// Best effort
				}
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "will/didInitiateConnection"
				String rawRequest = readRequest(clientSocketChannel);
				request = parseRequest(rawRequest, remoteAddress);
			} catch (RequestTooLargeIOException e) {
				// Exception provides a "too large"-flagged request with whatever data we could pull out of it
				request = e.getTooLargeRequest();
				if (remoteAddress != null)
					request = request.copy().remoteAddress(remoteAddress).finish();
			} catch (IllegalRequestException e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_REQUEST, "Unable to parse Server-Sent Event request")
						.throwable(e)
						.build());

				try {
					synchronized (channelLock) {
						writeFully(clientSocketChannel, FAILSAFE_HANDSHAKE_HTTP_400_RESPONSE);
					}
				} catch (Throwable t) {
					// best effort
				} finally {
					try {
						synchronized (channelLock) {
							clientSocketChannel.close();
						}
					} catch (IOException ignored) {
						// Nothing to do
					}
				}

				return;
			} catch (SocketTimeoutException e) {
				// TODO: in a future version, we might introduce lifecycle interceptor option here and for Server for "request timed out"
				throw e;
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_UNPARSEABLE_REQUEST, "Unable to parse Server-Sent Event request")
						.throwable(e)
						.build());
				throw e;
			}

			try {
				if (request.getHttpMethod() == HttpMethod.GET)
					validateNoRequestBodyHeaders(request);
			} catch (IllegalRequestException e) {
				try {
					MarshaledResponse response = getResponseMarshaler().get().forThrowable(request, e, null);
					synchronized (channelLock) {
						writeHandshakeResponseToChannel(clientSocketChannel, response);
					}
				} catch (Throwable t) {
					// best effort
				} finally {
					try {
						synchronized (channelLock) {
							clientSocketChannel.close();
						}
					} catch (IOException ignored) {
						// Nothing to do
					}
				}

				return;
			}

			// OK, we've successfully parsed the SSE handshake request - now, determine the resource path
			ResourcePathDeclaration resourcePathDeclaration = matchingResourcePath(request.getResourcePath()).orElse(null);

			if (resourcePathDeclaration != null)
				resourceMethod = getResourceMethodsByResourcePathDeclaration().get(resourcePathDeclaration);

			// Fast-path check for overload (authoritative limit enforcement occurs after handshake acceptance)
			if (resourcePathDeclaration != null && getActiveConnectionCount() >= getConcurrentConnectionLimit()) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_CONNECTION_REJECTED,
						format("Rejecting request to %s: Concurrent connection limit (%d) reached", request.getRawPathAndQuery(), getConcurrentConnectionLimit())).build());

				MarshaledResponse response = getResponseMarshaler().get().forServiceUnavailable(request, resourceMethod);

				try {
					synchronized (channelLock) {
						writeMarshaledResponseToChannel(clientSocketChannel, response);
					}
				} catch (Throwable t) {
					// best effort
				} finally {
					try {
						synchronized (channelLock) {
							clientSocketChannel.close();
						}
					} catch (IOException ignored) {
						// Nothing to do
					}
				}

				// Bail early
				return;
			}

			// We're now ready to write the handshake response - and then we keep the socket open for subsequent writes if handshake was accepted (otherwise we write the body and close).
			// To write the handshake response, we delegate to the Soklet instance, handing it the request we just parsed
			// and receiving a MarshaledResponse to write.  This lets the normal Soklet request processing flow occur.
			// Subsequent writes to the open socket (those following successful transmission of the "accepted" handshake response) are done via a ServerSentEventBroadcaster and sidestep the Soklet request processing flow.
			final Request requestForHandler = request;
			final ResourceMethod resourceMethodForHandler = resourceMethod;

			ScheduledExecutorService requestHandlerTimeoutExecutorService = getRequestHandlerTimeoutExecutorService().orElse(null);
			Thread handlerThread = Thread.currentThread();

			if (requestHandlerTimeoutExecutorService != null && !requestHandlerTimeoutExecutorService.isShutdown()) {
				handshakeTimeoutFutureRef.set(requestHandlerTimeoutExecutorService.schedule(() -> {
					if (!handshakeResponseWritten.compareAndSet(false, true))
						return;

					byte[] responseBytes = FAILSAFE_HANDSHAKE_HTTP_503_RESPONSE;

					try {
						ResponseMarshaler responseMarshaler = getResponseMarshaler().orElse(null);
						if (responseMarshaler != null) {
							MarshaledResponse response = responseMarshaler.forServiceUnavailable(requestForHandler, resourceMethodForHandler);
							responseBytes = createHandshakeHttpResponse(RequestResult.withMarshaledResponse(response).build());
						}
					} catch (Throwable t) {
						safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to prepare SSE handshake timeout response")
								.throwable(t)
								.build());
					}

					try {
						synchronized (channelLock) {
							writeFully(clientSocketChannel, responseBytes);
						}
					} catch (Throwable t) {
						safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to write SSE handshake timeout response")
								.throwable(t)
								.build());
					} finally {
						try {
							synchronized (channelLock) {
								clientSocketChannel.close();
							}
						} catch (IOException ignored) {
							// Nothing to do
						}
					}

					handlerThread.interrupt();
				}, Math.max(1L, getRequestHandlerTimeout().toMillis()), TimeUnit.MILLISECONDS));
			}

			try {
				getRequestHandler().get().handleRequest(requestForHandler, (@NonNull RequestResult requestResult) -> {
					if (!handshakeResponseWritten.compareAndSet(false, true))
						return;

					cancelTimeout(handshakeTimeoutFutureRef.getAndSet(null));

					// Set to the value Soklet processing gives us. Will be the empty Optional if no resource method was matched
					HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

					RequestResult effectiveRequestResult = requestResult;

					// Store a reference to the accepted handshake if we have it and there is capacity
					if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted accepted) {
						if (reserveConnectionSlot()) {
							connectionSlotReserved.set(true);
							handshakeAcceptedReference.set(accepted);
						} else {
							safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_CONNECTION_REJECTED,
									format("Rejecting request to %s: Concurrent connection limit (%d) reached", requestForHandler.getRawPathAndQuery(), getConcurrentConnectionLimit())).build());

							MarshaledResponse response = getResponseMarshaler().get().forServiceUnavailable(requestForHandler, requestResult.getResourceMethod().orElse(null));

							effectiveRequestResult = requestResult.copy()
									.marshaledResponse(response)
									.handshakeResult(null)
									.finish();
						}
					}

					byte[] handshakeHttpResponse;

					try {
						handshakeHttpResponse = createHandshakeHttpResponse(effectiveRequestResult);
					} catch (Throwable t) {
						// Should not happen, but if it does, we fall back to "rejected" handshake mode and write a failsafe 500 response
						safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to generate SSE handshake response")
								.throwable(t)
								.build());

						// Clear the accepted handshake reference in case it was set
						handshakeAcceptedReference.set(null);
						releaseReservedSlot(connectionSlotReserved);
						handshakeHttpResponse = FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE;
					}

					try {
						ByteBuffer byteBuffer = ByteBuffer.wrap(handshakeHttpResponse);

						synchronized (channelLock) {
							while (byteBuffer.hasRemaining())
								clientSocketChannel.write(byteBuffer);
						}
					} catch (Throwable t) {
						// We couldn't write a response to the client (maybe they disconnected).
						// Go through the rejected flow and close out the connection
						safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_WRITING_HANDSHAKE_RESPONSE_FAILED, "Unable to write SSE handshake response")
								.throwable(t)
								.build());

						// Clear the accepted handshake reference in case it was set
						handshakeAcceptedReference.set(null);
						releaseReservedSlot(connectionSlotReserved);
					}
				});
			} catch (Throwable t) {
				if (handshakeResponseWritten.compareAndSet(false, true)) {
					cancelTimeout(handshakeTimeoutFutureRef.getAndSet(null));
					try {
						synchronized (channelLock) {
							writeFully(clientSocketChannel, FAILSAFE_HANDSHAKE_HTTP_500_RESPONSE);
						}
					} catch (Throwable t2) {
						// best effort
					}
				}

				throw t;
			}

			// Happy path: register the channel for future ServerSentEvent writes and keep it open.
			// Otherwise, we're done immediately now that initial data has been written - shut it all down.
			HandshakeResult.Accepted handshakeAccepted = handshakeAcceptedReference.get();

			if (handshakeAccepted != null) {
				try {
					getLifecycleObserver().get().willEstablishServerSentEventConnection(request, resourceMethod);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(
									LogEventType.LIFECYCLE_OBSERVER_WILL_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
									format("An exception occurred while invoking %s::willEstablishServerSentEventConnection",
											LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.build());
				}

				Request willEstablishRequest = request;
				ResourceMethod willEstablishResourceMethod = resourceMethod;

				safelyCollectMetrics(
						format("An exception occurred while invoking %s::willEstablishServerSentEventConnection", MetricsCollector.class.getSimpleName()),
						willEstablishRequest,
						willEstablishResourceMethod,
						(metricsCollector) -> metricsCollector.willEstablishServerSentEventConnection(willEstablishRequest, willEstablishResourceMethod));

				clientSocketChannelRegistration = registerClientSocketChannel(clientSocketChannel, request, handshakeAccepted)
						.orElseThrow(() -> new IllegalStateException("SSE handshake accepted but connection could not be registered"));
				connectionSlotReserved.set(false);

				DefaultServerSentEventConnection serverSentEventConnection = clientSocketChannelRegistration.serverSentEventConnection();
				ServerSentEventConnection connectionSnapshot = serverSentEventConnection.getSnapshot();
				Request connectionRequest = connectionSnapshot.getRequest();
				ResourceMethod connectionResourceMethod = connectionSnapshot.getResourceMethod();

				try {
					getLifecycleObserver().get().didEstablishServerSentEventConnection(connectionSnapshot);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(
									LogEventType.LIFECYCLE_OBSERVER_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED,
									format("An exception occurred while invoking %s::didEstablishServerSentEventConnection",
											LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.build());
				}

				safelyCollectMetrics(
						format("An exception occurred while invoking %s::didEstablishServerSentEventConnection", MetricsCollector.class.getSimpleName()),
						connectionRequest,
						connectionResourceMethod,
						(metricsCollector) -> metricsCollector.didEstablishServerSentEventConnection(connectionSnapshot));

				BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue =
						serverSentEventConnection.getWriteQueue();

				while (true) {
					DefaultServerSentEventConnection.WriteQueueElement writeQueueElement;

					try {
						// Wait for an event/comment; if idle for heartbeatInterval, emit a heartbeat comment.
						writeQueueElement = writeQueue.poll(getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						// Interrupted during shutdown - exit cleanly
						Thread.currentThread().interrupt();
						break;
					}

					// Idle heartbeat
					if (writeQueueElement == null)
						writeQueueElement = DefaultServerSentEventConnection.WriteQueueElement.withComment(HEARTBEAT_COMMENT, System.nanoTime());

					if (writeQueueElement.isPoisonPill()) {
						// Encountered poison pill, exit...
						break;
					}

					DefaultServerSentEventConnection.PreSerializedEvent preSerializedEvent =
							writeQueueElement.getPreSerializedEvent().orElse(null);
					ServerSentEventComment serverSentEventComment = writeQueueElement.getComment().orElse(null);
					String comment = serverSentEventComment == null ? null : serverSentEventComment.getComment();
					ServerSentEvent serverSentEvent = preSerializedEvent != null
							? preSerializedEvent.getServerSentEvent()
							: null;

					byte[] payloadBytes;

					if (preSerializedEvent != null) {
						// It's a normal server-sent event
						payloadBytes = preSerializedEvent.getPayloadBytes();
					} else if (serverSentEventComment != null) {
						// It's a comment (includes heartbeats)
						String payload = formatCommentForResponse(comment);
						payloadBytes = payload == HEARTBEAT_COMMENT_PAYLOAD
								? HEARTBEAT_COMMENT_PAYLOAD_BYTES
								: payload.getBytes(StandardCharsets.UTF_8);
					} else {
						throw new IllegalStateException("Not sure what to do; no Server-Sent Event or comment available");
					}

					ByteBuffer byteBuffer = ByteBuffer.wrap(payloadBytes);

					if (serverSentEventComment != null) {
						try {
							getLifecycleObserver().get().willWriteServerSentEventComment(connectionSnapshot, serverSentEventComment);
						} catch (Throwable t) {
							safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::willWriteServerSentEventComment", LifecycleObserver.class.getSimpleName()))
									.throwable(t)
									.build());
						}

						safelyCollectMetrics(
								format("An exception occurred while invoking %s::willWriteServerSentEventComment", MetricsCollector.class.getSimpleName()),
								connectionRequest,
								connectionResourceMethod,
								(metricsCollector) -> metricsCollector.willWriteServerSentEventComment(connectionSnapshot, serverSentEventComment));
					}

					if (serverSentEvent != null) {
						try {
							getLifecycleObserver().get().willWriteServerSentEvent(connectionSnapshot, serverSentEvent);
						} catch (Throwable t) {
							safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::willWriteServerSentEvent", LifecycleObserver.class.getSimpleName()))
									.throwable(t)
									.build());
						}

						safelyCollectMetrics(
								format("An exception occurred while invoking %s::willWriteServerSentEvent", MetricsCollector.class.getSimpleName()),
								connectionRequest,
								connectionResourceMethod,
								(metricsCollector) -> metricsCollector.willWriteServerSentEvent(connectionSnapshot, serverSentEvent));
					}

					Duration deliveryLag = null;
					Integer payloadByteCount = null;
					Integer queueDepth = null;

					long enqueuedAtNanos = writeQueueElement.getEnqueuedAtNanos();
					long nowNanos = System.nanoTime();

					if (enqueuedAtNanos > 0L)
						deliveryLag = Duration.ofNanos(Math.max(0L, nowNanos - enqueuedAtNanos));

					if (payloadBytes != null)
						payloadByteCount = payloadBytes.length;

					queueDepth = writeQueue.size();

					writeStarted = Instant.now();
					Throwable writeThrowable = null;
					AtomicBoolean writeTimedOut = null;
					ScheduledFuture<?> writeTimeoutFuture = null;
					long writeTimeoutMillis = getWriteTimeout().toMillis();

					if (writeTimeoutMillis > 0 && requestHandlerTimeoutExecutorService != null && !requestHandlerTimeoutExecutorService.isShutdown()) {
						AtomicBoolean timedOut = new AtomicBoolean(false);
						writeTimedOut = timedOut;
						writeTimeoutFuture = requestHandlerTimeoutExecutorService.schedule(() -> {
							timedOut.set(true);
							try {
								clientSocketChannel.close();
							} catch (IOException ignored) {
								// Nothing to do
							}
						}, Math.max(1L, writeTimeoutMillis), TimeUnit.MILLISECONDS);
					}

					try {
						while (byteBuffer.hasRemaining()) {
							clientSocketChannel.write(byteBuffer);
						}
					} catch (Throwable t) {
						writeThrowable = t;
					} finally {
						cancelTimeout(writeTimeoutFuture);
						if (writeTimedOut != null && writeTimedOut.get()
								&& (writeThrowable == null || writeThrowable instanceof ClosedChannelException)) {
							writeThrowable = new SocketTimeoutException("SSE write timed out");
						}

						Instant writeFinished = Instant.now();
						Duration writeDuration = Duration.between(writeStarted, writeFinished);
						Throwable writeThrowableSnapshot = writeThrowable;
						Duration deliveryLagSnapshot = deliveryLag;
						Integer payloadByteCountSnapshot = payloadByteCount;
						Integer queueDepthSnapshot = queueDepth;

						if (serverSentEvent != null) {
							if (writeThrowableSnapshot != null) {
								try {
									getLifecycleObserver().get().didFailToWriteServerSentEvent(connectionSnapshot, serverSentEvent, writeDuration, writeThrowableSnapshot);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::didFailToWriteServerSentEvent", LifecycleObserver.class.getSimpleName()))
											.throwable(t)
											.build());
								}

								safelyCollectMetrics(
										format("An exception occurred while invoking %s::didFailToWriteServerSentEvent", MetricsCollector.class.getSimpleName()),
										connectionRequest,
										connectionResourceMethod,
										(metricsCollector) -> metricsCollector.didFailToWriteServerSentEvent(
												connectionSnapshot,
												serverSentEvent,
												writeDuration,
												writeThrowableSnapshot,
												deliveryLagSnapshot,
												payloadByteCountSnapshot,
												queueDepthSnapshot));
							} else {
								try {
									getLifecycleObserver().get().didWriteServerSentEvent(connectionSnapshot, serverSentEvent, writeDuration);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_FAILED, format("An exception occurred while invoking %s::didWriteServerSentEvent", LifecycleObserver.class.getSimpleName()))
											.throwable(t)
											.build());
								}

								safelyCollectMetrics(
										format("An exception occurred while invoking %s::didWriteServerSentEvent", MetricsCollector.class.getSimpleName()),
										connectionRequest,
										connectionResourceMethod,
										(metricsCollector) -> metricsCollector.didWriteServerSentEvent(
												connectionSnapshot,
												serverSentEvent,
												writeDuration,
												deliveryLagSnapshot,
												payloadByteCountSnapshot,
												queueDepthSnapshot));
							}
						} else if (serverSentEventComment != null) {
							if (writeThrowableSnapshot != null) {
								try {
									getLifecycleObserver().get().didFailToWriteServerSentEventComment(connectionSnapshot, serverSentEventComment, writeDuration, writeThrowableSnapshot);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::didFailToWriteServerSentEventComment", LifecycleObserver.class.getSimpleName()))
											.throwable(t)
											.build());
								}

								safelyCollectMetrics(
										format("An exception occurred while invoking %s::didFailToWriteServerSentEventComment", MetricsCollector.class.getSimpleName()),
										connectionRequest,
										connectionResourceMethod,
										(metricsCollector) -> metricsCollector.didFailToWriteServerSentEventComment(
												connectionSnapshot,
												serverSentEventComment,
												writeDuration,
												writeThrowableSnapshot,
												deliveryLagSnapshot,
												payloadByteCountSnapshot,
												queueDepthSnapshot));
							} else {
								try {
									getLifecycleObserver().get().didWriteServerSentEventComment(connectionSnapshot, serverSentEventComment, writeDuration);
								} catch (Throwable t) {
									safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_WRITE_SERVER_SENT_EVENT_COMMENT_FAILED, format("An exception occurred while invoking %s::didWriteServerSentEventComment", LifecycleObserver.class.getSimpleName()))
											.throwable(t)
											.build());
								}

								safelyCollectMetrics(
										format("An exception occurred while invoking %s::didWriteServerSentEventComment", MetricsCollector.class.getSimpleName()),
										connectionRequest,
										connectionResourceMethod,
										(metricsCollector) -> metricsCollector.didWriteServerSentEventComment(
												connectionSnapshot,
												serverSentEventComment,
												writeDuration,
												deliveryLagSnapshot,
												payloadByteCountSnapshot,
												queueDepthSnapshot));
							}
						}

						if (writeThrowableSnapshot != null)
							throw writeThrowableSnapshot;
					}
				}
			}
		} catch (Throwable t) {
			throwable = t;

			// If we attempted to establish (willEstablish fired), but registration is null, it means we failed before didEstablish could fire.
			if (handshakeAcceptedReference.get() != null && clientSocketChannelRegistration == null) {
				try {
					getLifecycleObserver().get().didFailToEstablishServerSentEventConnection(request, resourceMethod, t);
				} catch (Throwable t1) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::didFailToEstablishServerSentEventConnection", LifecycleObserver.class.getSimpleName()))
							.throwable(t1)
							.build());
				}

				Request failedRequest = request;
				ResourceMethod failedResourceMethod = resourceMethod;

				safelyCollectMetrics(
						format("An exception occurred while invoking %s::didFailToEstablishServerSentEventConnection", MetricsCollector.class.getSimpleName()),
						failedRequest,
						failedResourceMethod,
						(metricsCollector) -> metricsCollector.didFailToEstablishServerSentEventConnection(failedRequest, failedResourceMethod, t));
			}

			if (t instanceof InterruptedException)
				Thread.currentThread().interrupt();
		} finally {
			cancelTimeout(handshakeTimeoutFutureRef.getAndSet(null));

			// First, close the channel itself...
			if (clientSocketChannel != null) {
				try {
					synchronized (channelLock) {
						clientSocketChannel.close();
					}
				} catch (Exception exception) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to close Server-Sent Event connection socket channel")
							.throwable(exception)
							.build());
				}
			}

			// ...then unregister from broadcaster (prevents race with broadcasts)
			if (clientSocketChannelRegistration != null) {
				DefaultServerSentEventConnection serverSentEventConnection = clientSocketChannelRegistration.serverSentEventConnection();
				ServerSentEventConnection connectionSnapshot = serverSentEventConnection.getSnapshot();
				Request connectionRequest = connectionSnapshot.getRequest();
				ResourceMethod connectionResourceMethod = connectionSnapshot.getResourceMethod();

				ServerSentEventConnection.TerminationReason terminationReason =
						serverSentEventConnection.getTerminationReason().orElse(null);

				if (terminationReason == null) {
					if (isStopping())
						terminationReason = ServerSentEventConnection.TerminationReason.SERVER_STOP;
					else if (isRemoteClose(throwable))
						terminationReason = ServerSentEventConnection.TerminationReason.REMOTE_CLOSE;
					else if (throwable != null)
						terminationReason = ServerSentEventConnection.TerminationReason.ERROR;
					else
						terminationReason = ServerSentEventConnection.TerminationReason.UNKNOWN;

					serverSentEventConnection.setTerminationReason(terminationReason);
				}

				ServerSentEventConnection.TerminationReason effectiveTerminationReason = terminationReason;
				Throwable terminationThrowable = throwable;

				try {
					getLifecycleObserver().get().willTerminateServerSentEventConnection(
							connectionSnapshot,
							effectiveTerminationReason,
							terminationThrowable);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_WILL_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::willTerminateServerSentEventConnection", LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.build());
				}

				safelyCollectMetrics(
						format("An exception occurred while invoking %s::willTerminateServerSentEventConnection", MetricsCollector.class.getSimpleName()),
						connectionRequest,
						connectionResourceMethod,
						(metricsCollector) -> metricsCollector.willTerminateServerSentEventConnection(
								connectionSnapshot,
								effectiveTerminationReason,
								terminationThrowable));

				try {
					clientSocketChannelRegistration.broadcaster().unregisterServerSentEventConnection(serverSentEventConnection, false);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to de-register Server-Sent Event connection")
							.throwable(t)
							.build());
				}

				// Cleanup empty broadcaster
				maybeCleanupBroadcaster(clientSocketChannelRegistration.broadcaster());

				Instant connectionFinished = Instant.now();
				Duration connectionDuration = Duration.between(
						serverSentEventConnection.getEstablishedAt(),
						connectionFinished);

				try {
					getLifecycleObserver().get().didTerminateServerSentEventConnection(
							connectionSnapshot,
							connectionDuration,
							effectiveTerminationReason,
							terminationThrowable);
				} catch (Throwable t) {
					safelyLog(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED, format("An exception occurred while invoking %s::didTerminateServerSentEventConnection", LifecycleObserver.class.getSimpleName()))
							.throwable(t)
							.build());
				}

				safelyCollectMetrics(
						format("An exception occurred while invoking %s::didTerminateServerSentEventConnection", MetricsCollector.class.getSimpleName()),
						connectionRequest,
						connectionResourceMethod,
						(metricsCollector) -> metricsCollector.didTerminateServerSentEventConnection(
								connectionSnapshot,
								connectionDuration,
								effectiveTerminationReason,
								terminationThrowable));
			}

			releaseReservedSlot(connectionSlotReserved);
		}
	}

	@NonNull
	private byte[] createHandshakeHttpResponse(@NonNull RequestResult requestResult) throws IOException {
		requireNonNull(requestResult);

		MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();
		HandshakeResult handshakeResult = requestResult.getHandshakeResult().orElse(null);

		// Shared buffer for building the header section
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);
				 OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, StandardCharsets.ISO_8859_1);
				 PrintWriter printWriter = new PrintWriter(outputStreamWriter, false)) {

			if (handshakeResult != null && handshakeResult instanceof HandshakeResult.Accepted) {
				final Set<String> ILLEGAL_LOWERCASE_HEADER_NAMES = Set.of("content-length");

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
				throw new IllegalStateException(format("Unsupported %s: %s", HandshakeResult.class.getSimpleName(), handshakeResult));
			}
		}
	}

	@NonNull
	private static byte[] createFailsafeHandshakeHttpResponse(@NonNull StatusCode statusCode) {
		requireNonNull(statusCode);

		String reasonPhrase = statusCode.getReasonPhrase();
		String body = format("HTTP %d: %s", statusCode.getStatusCode(), reasonPhrase);
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

		String response =
				"HTTP/1.1 " + statusCode.getStatusCode() + " " + reasonPhrase + "\r\n" +
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

	private static boolean isLineBreakChar(char c) {
		return c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == '\u2028' || c == '\u2029';
	}

	private static void writeFully(@NonNull SocketChannel socketChannel,
																 @NonNull byte[] bytes) throws IOException {
		requireNonNull(socketChannel);
		requireNonNull(bytes);

		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining())
			socketChannel.write(buffer);
	}

	private void writeHandshakeResponseToChannel(@NonNull SocketChannel socketChannel,
																							 @NonNull MarshaledResponse marshaledResponse) throws IOException {
		requireNonNull(socketChannel);
		requireNonNull(marshaledResponse);

		byte[] responseBytes = createHandshakeHttpResponse(RequestResult.withMarshaledResponse(marshaledResponse).build());
		writeFully(socketChannel, responseBytes);
	}

	private void cancelTimeout(@Nullable ScheduledFuture<?> timeoutFuture) {
		if (timeoutFuture != null)
			timeoutFuture.cancel(false);
	}

	// Helper to write the marshaled response directly to the channel
	private void writeMarshaledResponseToChannel(@NonNull SocketChannel socketChannel,
																							 @NonNull MarshaledResponse marshaledResponse) throws IOException {
		requireNonNull(socketChannel);
		requireNonNull(marshaledResponse);

		// Write Status Line
		String statusLine = format("HTTP/1.1 %d\r\n", marshaledResponse.getStatusCode());
		writeFully(socketChannel, statusLine.getBytes(StandardCharsets.UTF_8));

		// Write Headers
		for (Entry<String, Set<String>> entry : marshaledResponse.getHeaders().entrySet()) {
			for (String value : entry.getValue()) {
				String header = entry.getKey() + ": " + value + "\r\n";
				writeFully(socketChannel, header.getBytes(StandardCharsets.UTF_8));
			}
		}

		// Write Cookies
		for (ResponseCookie cookie : marshaledResponse.getCookies()) {
			String header = "Set-Cookie: " + cookie.toSetCookieHeaderRepresentation() + "\r\n";
			writeFully(socketChannel, header.getBytes(StandardCharsets.UTF_8));
		}

		// Headers end
		writeFully(socketChannel, new byte[]{'\r', '\n'});

		// Write Body
		if (marshaledResponse.getBody().isPresent())
			writeFully(socketChannel, marshaledResponse.getBody().get());
	}

	@NonNull
	protected String formatCommentForResponse(@NonNull String comment) {
		requireNonNull(comment);

		if (comment.isEmpty())
			return HEARTBEAT_COMMENT_PAYLOAD;

		StringBuilder stringBuilder = new StringBuilder(DEFAULT_SSE_BUILDER_CAPACITY);
		int len = comment.length();
		int start = 0;

		for (int i = 0; i < len; i++) {
			char c = comment.charAt(i);

			if (isLineBreakChar(c)) {
				appendCommentLine(stringBuilder, comment, start, i);

				if (c == '\r' && (i + 1) < len && comment.charAt(i + 1) == '\n')
					i++;

				start = i + 1;
			}
		}

		appendCommentLine(stringBuilder, comment, start, len);
		stringBuilder.append('\n');

		return stringBuilder.toString();
	}

	private void appendCommentLine(@NonNull StringBuilder stringBuilder,
																 @NonNull String comment,
																 int start,
																 int end) {
		stringBuilder.append(':');

		if (end > start)
			stringBuilder.append(' ').append(comment, start, end);

		stringBuilder.append('\n');
	}

	@NonNull
	private static String formatServerSentEventForResponse(@NonNull ServerSentEvent serverSentEvent,
																												 @NonNull StringBuilder sb) {
		requireNonNull(serverSentEvent);
		requireNonNull(sb);

		sb.setLength(0);
		boolean hasField = false;

		// 1. Event Name
		String event = serverSentEvent.getEvent().orElse(null);
		if (event != null) {
			sb.append("event: ").append(event).append('\n');
			hasField = true;
		}

		// 2. ID
		String id = serverSentEvent.getId().orElse(null);
		if (id != null) {
			sb.append("id: ").append(id).append('\n');
			hasField = true;
		}

		// 3. Retry
		Duration retry = serverSentEvent.getRetry().orElse(null);

		if (retry != null) {
			sb.append("retry: ").append(retry.toMillis()).append('\n');
			hasField = true;
		}

		// 4. Data
		// We perform a manual scan to avoid Regex compilation and array allocation.
		String data = serverSentEvent.getData().orElse(null);

		if (data != null) {
			hasField = true;
			int len = data.length();

			if (len == 0) {
				// Emulates split behavior on empty string: produces one empty "data:" line
				sb.append("data: \n");
			} else {
				int start = 0;

				for (int i = 0; i < len; i++) {
					char c = data.charAt(i);

					if (isLineBreakChar(c)) {
						// Append the current segment
						sb.append("data: ").append(data, start, i).append('\n');

						// Handle CRLF (\r\n) by skipping the next char if it is \n
						if (c == '\r' && (i + 1) < len && data.charAt(i + 1) == '\n') {
							i++;
						}
						start = i + 1;
					}
				}

				// Append the final segment.
				// If the string ended with a newline, 'start' will equal 'len'.
				// string.substring(len, len) returns "", so we get "data: \n".
				// This emulates the behavior of data.split("\\R", -1) which
				// preserves trailing empty strings.
				sb.append("data: ").append(data, start, len).append('\n');
			}
		}

		// Per SSE spec, an event with no fields is a keep-alive comment.
		if (!hasField)
			return HEARTBEAT_COMMENT_PAYLOAD;

		// Terminate the block
		sb.append('\n');

		return sb.toString();
	}

	// Internal snapshot - the only implementation users ever see
	private record ServerSentEventConnectionSnapshot(
			@NonNull Request request,
			@NonNull ResourceMethod resourceMethod,
			@NonNull Instant establishedAt,
			@Nullable Object clientContext
	) implements ServerSentEventConnection {
		public ServerSentEventConnectionSnapshot {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(establishedAt);
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Override
		@NonNull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@NonNull
		@Override
		public Optional<Object> getClientContext() {
			return Optional.ofNullable(clientContext);
		}
	}

	// We deliberately don't explicitly implement the ServerSentEventConnection interface - we never want a direct ref to this to leak out to clients.
	@ThreadSafe
	private static final class DefaultServerSentEventConnection /* implements ServerSentEventConnection */ {
		@NonNull
		private final Request request;
		@NonNull
		private final ResourceMethod resourceMethod;
		@Nullable
		private final Object clientContext;
		@NonNull
		private final BlockingQueue<WriteQueueElement> writeQueue;
		@NonNull
		private final Instant establishedAt;
		@NonNull
		private final SocketChannel socketChannel;
		@NonNull
		private final AtomicBoolean closing;
		@NonNull
		private final AtomicReference<ServerSentEventConnection.TerminationReason> terminationReason;
		@NonNull
		private final ServerSentEventConnectionSnapshot snapshot;

		@ThreadSafe
		static final class PreSerializedEvent {
			@NonNull
			private final ServerSentEvent serverSentEvent;
			@NonNull
			private final byte[] payloadBytes;

			PreSerializedEvent(@NonNull ServerSentEvent serverSentEvent,
												 @NonNull byte[] payloadBytes) {
				this.serverSentEvent = requireNonNull(serverSentEvent);
				this.payloadBytes = requireNonNull(payloadBytes);
			}

			@NonNull
			public ServerSentEvent getServerSentEvent() {
				return this.serverSentEvent;
			}

			@NonNull
			public byte[] getPayloadBytes() {
				return this.payloadBytes;
			}
		}

		@ThreadSafe
		static final class WriteQueueElement {
			@Nullable
			private final PreSerializedEvent preSerializedEvent;
			@Nullable
			private final ServerSentEventComment comment;
			private final boolean poisonPill;
			private final long enqueuedAtNanos;

			private static final WriteQueueElement POISON_PILL = new WriteQueueElement(null, null, true, -1L);

			@NonNull
			public static WriteQueueElement withPreSerializedEvent(@NonNull PreSerializedEvent preSerializedEvent,
																														 long enqueuedAtNanos) {
				requireNonNull(preSerializedEvent);
				return new WriteQueueElement(preSerializedEvent, null, false, enqueuedAtNanos);
			}

			@NonNull
			public static WriteQueueElement withComment(@NonNull ServerSentEventComment serverSentEventComment,
																										 long enqueuedAtNanos) {
				requireNonNull(serverSentEventComment);
				return new WriteQueueElement(null, serverSentEventComment, false, enqueuedAtNanos);
			}

			@NonNull
			public static WriteQueueElement poisonPill() {
				return POISON_PILL;
			}

			private WriteQueueElement(@Nullable PreSerializedEvent preSerializedEvent,
																@Nullable ServerSentEventComment comment,
																boolean poisonPill,
																long enqueuedAtNanos) {
				if (poisonPill) {
					if (preSerializedEvent != null || comment != null)
						throw new IllegalStateException("Poison pill cannot include a payload");
				} else {
					if (preSerializedEvent == null && comment == null)
						throw new IllegalStateException("Must provide either a server-sent event or a comment");

					if (preSerializedEvent != null && comment != null)
						throw new IllegalStateException("Must provide either a server-sent event or a comment; not both");
				}

				this.preSerializedEvent = preSerializedEvent;
				this.comment = comment;
				this.poisonPill = poisonPill;
				this.enqueuedAtNanos = enqueuedAtNanos;
			}

			@NonNull
			public Optional<PreSerializedEvent> getPreSerializedEvent() {
				return Optional.ofNullable(this.preSerializedEvent);
			}

			@NonNull
			public Optional<ServerSentEventComment> getComment() {
				return Optional.ofNullable(this.comment);
			}

			public long getEnqueuedAtNanos() {
				return this.enqueuedAtNanos;
			}

			public boolean isPoisonPill() {
				return this.poisonPill;
			}
		}

		public DefaultServerSentEventConnection(@NonNull Request request,
																						@NonNull ResourceMethod resourceMethod,
																						@Nullable Object clientContext,
																						@NonNull Integer connectionQueueCapacity,
																						@NonNull SocketChannel socketChannel) {
			requireNonNull(request);
			requireNonNull(resourceMethod);
			requireNonNull(connectionQueueCapacity);
			requireNonNull(socketChannel);

			this.request = request;
			this.resourceMethod = resourceMethod;
			this.clientContext = clientContext;
			this.writeQueue = new ArrayBlockingQueue<>(connectionQueueCapacity);
			this.establishedAt = Instant.now();
			this.socketChannel = socketChannel;
			this.closing = new AtomicBoolean(false);
			this.terminationReason = new AtomicReference<>();

			// Cache off an immutable data-only snapshot.
			// This can be safely exposed to client code without worrying about holding onto internal state (e.g. write queue)
			this.snapshot = new ServerSentEventConnectionSnapshot(request, resourceMethod, establishedAt, clientContext);
		}

		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@NonNull
		public Optional<Object> getClientContext() {
			return Optional.ofNullable(this.clientContext);
		}

		@NonNull
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@NonNull
		public BlockingQueue<WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}

		@NonNull
		public SocketChannel getSocketChannel() {
			return this.socketChannel;
		}

		@NonNull
		public AtomicBoolean getClosing() {
			return this.closing;
		}

		public void setTerminationReason(ServerSentEventConnection.@NonNull TerminationReason reason) {
			requireNonNull(reason);
			this.terminationReason.compareAndSet(null, reason);
		}

		@NonNull
		public Optional<ServerSentEventConnection.TerminationReason> getTerminationReason() {
			return Optional.ofNullable(this.terminationReason.get());
		}

		@NonNull
		public ServerSentEventConnectionSnapshot getSnapshot() {
			return this.snapshot;
		}
	}

	private record ClientSocketChannelRegistration(@NonNull DefaultServerSentEventConnection serverSentEventConnection,
																								 @NonNull DefaultServerSentEventBroadcaster broadcaster) {
		public ClientSocketChannelRegistration {
			requireNonNull(serverSentEventConnection);
			requireNonNull(broadcaster);
		}
	}

	@NonNull
	private Optional<ClientSocketChannelRegistration> registerClientSocketChannel(@NonNull SocketChannel clientSocketChannel,
																																									@NonNull Request request,
																																									HandshakeResult.@NonNull Accepted handshakeAccepted) {
		requireNonNull(clientSocketChannel);
		requireNonNull(request);
		requireNonNull(handshakeAccepted);

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourcePath resourcePath = request.getResourcePath();

		if (!matchingResourcePath(resourcePath).isPresent())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		// Create the connection (write queue owned per connection)
		DefaultServerSentEventConnection serverSentEventConnection = new DefaultServerSentEventConnection(
				request,
				resourceMethod,
				handshakeAccepted.getClientContext().orElse(null),
				getConnectionQueueCapacity(),
				clientSocketChannel
		);

		// If a client initializer exists, hand it the unicaster to support Last-Event-ID "catch up" scenarios
		ServerSentEventUnicaster serverSentEventUnicaster =
				new DefaultServerSentEventUnicaster(resourcePath, serverSentEventConnection.getWriteQueue());

		handshakeAccepted.getClientInitializer().ifPresent((clientInitializer) -> {
			clientInitializer.accept(serverSentEventUnicaster);
		});

		// Now that the client initializer has run (if present), enqueue a single "heartbeat" comment to immediately "flush"/verify the connection if configured to do so
		if (getVerifyConnectionOnceEstablished())
			serverSentEventConnection.getWriteQueue()
					.offer(DefaultServerSentEventConnection.WriteQueueElement.withComment(HEARTBEAT_COMMENT, System.nanoTime()));

		DefaultServerSentEventBroadcaster broadcaster =
				registerConnectionWithBroadcaster(resourcePath, resourceMethod, serverSentEventConnection);

		// Remove from idle LRU *outside* of any broadcastersByResourcePath compute() to avoid deadlocks.
		try {
			getIdleBroadcastersByResourcePath().remove(resourcePath, broadcaster);
		} catch (Throwable ignored) {
			// best-effort; never fail connection establishment due to cache bookkeeping
		}

		return Optional.of(new ClientSocketChannelRegistration(serverSentEventConnection, broadcaster));
	}

	@ThreadSafe
	protected static class DefaultServerSentEventUnicaster implements ServerSentEventUnicaster {
		@NonNull
		private final ResourcePath resourcePath;
		@NonNull
		private final BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue;

		public DefaultServerSentEventUnicaster(@NonNull ResourcePath resourcePath,
																					 @NonNull BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue) {
			requireNonNull(resourcePath);
			requireNonNull(writeQueue);

			this.resourcePath = resourcePath;
			this.writeQueue = writeQueue;
		}

		@Override
		public void unicastEvent(@NonNull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			DefaultServerSentEventConnection.PreSerializedEvent preSerializedEvent = preSerializeServerSentEvent(serverSentEvent);

			if (!getWriteQueue().offer(DefaultServerSentEventConnection.WriteQueueElement.withPreSerializedEvent(preSerializedEvent, System.nanoTime())))
				throw new IllegalStateException("SSE client initializer exceeded connection write-queue capacity");
		}

		@Override
		public void unicastComment(@NonNull ServerSentEventComment serverSentEventComment) {
			requireNonNull(serverSentEventComment);

			if (!getWriteQueue().offer(DefaultServerSentEventConnection.WriteQueueElement.withComment(serverSentEventComment, System.nanoTime())))
				throw new IllegalStateException("SSE client initializer exceeded connection write-queue capacity");
		}

		@NonNull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		protected BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> getWriteQueue() {
			return this.writeQueue;
		}
	}

	protected void maybeCleanupBroadcaster(@NonNull DefaultServerSentEventBroadcaster broadcaster) {
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
		} finally {
			// Best-effort: ensure idle cache doesn't keep a strong ref to a broadcaster that is (or is about to be) unmapped.
			try {
				getIdleBroadcastersByResourcePath().remove(broadcaster.getResourcePath(), broadcaster);
			} catch (Throwable ignored) {
				// ignore
			}
		}
	}

	@NonNull
	protected Request parseRequest(@NonNull String rawRequest,
																 @Nullable InetSocketAddress remoteAddress) {
		requireNonNull(rawRequest);

		rawRequest = trimAggressivelyToNull(rawRequest);

		if (rawRequest == null)
			throw new IllegalRequestException("Server-Sent Event HTTP request has no data");

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

		// Server-Sent Event handshakes are GET requests. We only read headers and let Soklet
		// generate a 405 for non-GET methods.

		// First line is the URL and the rest are headers.
		// Line 1: GET /testing?one=two HTTP/1.1
		// Line 2: Accept-Encoding: gzip, deflate, br, zstd
		// ...and so forth.

		Request.RawBuilder requestBuilder = null;
		List<String> headerLines = new ArrayList<>();
		int hostHeaderCount = 0;
		String hostHeaderValue = null;

		// Preserve empty lines so we can detect the end-of-headers
		List<String> lines = splitRequestLines(rawRequest);

		for (int i = 0; i < lines.size(); i++) {
			String rawLine = lines.get(i);

			// First line: request line
			if (requestBuilder == null) {
				String line = trimAggressivelyToNull(rawLine);
				if (line == null)
					continue;

				String[] components = line.trim().split("\\s+");
				if (components.length != 3)
					throw new IllegalRequestException(format("Malformed Server-Sent Event request line '%s'. Expected '<METHOD> <request-target> HTTP/1.1'", line));

				String rawHttpMethod = components[0];
				String rawUrl = components[1];
				String rawVersion = components[2];

				requireAsciiToken(rawHttpMethod, "HTTP method");
				requireAsciiToken(rawUrl, "request target");
				requireAsciiToken(rawVersion, "HTTP version");

				if (!"HTTP/1.1".equalsIgnoreCase(rawVersion))
					throw new IllegalRequestException(format("Unsupported HTTP version '%s' for Server-Sent Event requests", rawVersion));

				HttpMethod httpMethod;
				String normalizedMethod = rawHttpMethod.toUpperCase(Locale.ENGLISH);

				try {
					httpMethod = HttpMethod.valueOf(normalizedMethod);
				} catch (IllegalArgumentException e) {
					throw new IllegalRequestException(format("Malformed Server-Sent Event request line '%s'. Unable to parse HTTP method.", line), e);
				}

				requestBuilder = Request.withRawUrl(httpMethod, rawUrl);
				continue;
			}

			// End-of-headers: blank line or whitespace-only line
			if (rawLine.isEmpty() || rawLine.trim().isEmpty())
				break;

			if (rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t')
				throw new IllegalRequestException("Header folding is not supported for Server-Sent Event requests");

			// Header line
			int indexOfFirstColon = rawLine.indexOf(':');

			if (indexOfFirstColon <= 0)
				throw new IllegalRequestException(format("Malformed Server-Sent Event request header line '%s'. Expected 'Header-Name: Value'", rawLine));

			String headerName = rawLine.substring(0, indexOfFirstColon);
			validateHeaderName(headerName);

			String headerValue = rawLine.substring(indexOfFirstColon + 1);
			validateHeaderValue(headerName, headerValue);

			if ("Host".equalsIgnoreCase(headerName)) {
				hostHeaderCount++;
				if (hostHeaderCount == 1)
					hostHeaderValue = headerValue;
			}

			if ("Expect".equalsIgnoreCase(headerName))
				throw new IllegalRequestException("Expect header is not supported for Server-Sent Event requests");

			headerLines.add(rawLine);
		}

		if (hostHeaderCount == 0)
			throw new IllegalRequestException("Missing Host header");

		if (hostHeaderCount > 1)
			throw new IllegalRequestException("Multiple Host headers are not allowed");

		if (!HostHeaderValidator.isValidHostHeaderValue(hostHeaderValue))
			throw new IllegalRequestException("Invalid Host header value");

		Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(headerLines);

		return requestBuilder.idGenerator(getIdGenerator()).headers(headers).remoteAddress(remoteAddress).build();
	}

	private static void requireAsciiToken(@NonNull String value, @NonNull String field) {
		if (value.isEmpty())
			throw new IllegalRequestException(format("Missing %s in Server-Sent Event request line", field));

		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) > 0x7F)
				throw new IllegalRequestException(format("Non-ASCII %s in Server-Sent Event request line", field));
		}
	}

	private static void validateHeaderName(@NonNull String name) {
		if (name.isEmpty())
			throw new IllegalRequestException("Header name is blank");

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c > 0x7F || !isTchar(c))
				throw new IllegalRequestException(format("Illegal header name '%s'. Offending character: '%s'", name, Utilities.printableChar(c)));
		}
	}

	private static boolean isTchar(char c) {
		return c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
				c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~' ||
				Character.isLetterOrDigit(c);
	}

	private static void validateHeaderValue(@NonNull String name, @NonNull String value) {
		requireNonNull(name);
		requireNonNull(value);

		// Reject control characters; obs-text (0x80-0xFF) is allowed.
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

	protected void validateNoRequestBodyHeaders(@NonNull Request request) {
		requireNonNull(request);

		Map<String, Set<String>> headers = request.getHeaders();
		for (String headerName : headers.keySet()) {
			if (headerName == null)
				continue;
			for (int i = 0; i < headerName.length(); i++) {
				if (headerName.charAt(i) > 0x7F)
					throw new IllegalRequestException("Non-ASCII header names are not allowed for Server-Sent Event requests");
			}
		}

		Set<String> transferEncodingValues = headers.get("Transfer-Encoding");

		if (transferEncodingValues != null && !transferEncodingValues.isEmpty())
			throw new IllegalRequestException("Transfer-Encoding is not allowed for Server-Sent Event requests");

		Set<String> contentLengthValues = headers.get("Content-Length");

		if (contentLengthValues != null && !contentLengthValues.isEmpty()) {
			if (contentLengthValues.size() != 1)
				throw new IllegalRequestException("Multiple Content-Length headers are not allowed for Server-Sent Event requests");

			String contentLengthValue = contentLengthValues.iterator().next();

			if (contentLengthValue == null || contentLengthValue.trim().isEmpty())
				throw new IllegalRequestException("Invalid Content-Length header value");

			long contentLength;

			try {
				contentLength = Long.parseLong(contentLengthValue.trim());
			} catch (NumberFormatException e) {
				throw new IllegalRequestException("Invalid Content-Length header value", e);
			}

			if (contentLength != 0)
				throw new IllegalRequestException("Non-zero Content-Length is not allowed for Server-Sent Event requests");
		}
	}

	@NonNull
	protected String readRequest(@NonNull SocketChannel clientSocketChannel) throws IOException {
		requireNonNull(clientSocketChannel);

		// Because reads from the socket channel are blocking, there is no way to specify a timeout for it.
		// We work around this by performing the read in a virtual thread, and use the timeout functionality
		// built in to Futures to interrupt the thread if it doesn't finish in time.
		Future<String> readFuture = null;

		// How long to wait for the request to be read (minimum of 1 millisecond)
		long timeoutMillis = Math.max(1L, getRequestTimeout().toMillis());

		try {
			ExecutorService requestReaderExecutorService = getRequestReaderExecutorService().orElse(null);

			if (requestReaderExecutorService == null || requestReaderExecutorService.isShutdown())
				throw new IOException("Server is shutting down");

			readFuture = requestReaderExecutorService.submit(() -> {
				ByteBuffer buffer = ByteBuffer.allocate(getRequestReadBufferSizeInBytes());

				// Use ISO-8859-1 so HTTP header bytes map 1:1 while remaining deterministic across hosts.
				CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder()
						.onMalformedInput(CodingErrorAction.REPLACE)
						.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);

				CharBuffer charBuffer = CharBuffer.allocate(getRequestReadBufferSizeInBytes());

				StringBuilder requestBuilder = new StringBuilder();
				boolean headersComplete = false;
				int totalBytesRead = 0;

				while (!headersComplete) {
					int bytesRead = clientSocketChannel.read(buffer);

					// Standard interruption/EOF checks
					if (Thread.interrupted())
						throw new InterruptedIOException("Thread interrupted while reading request data");
					if (bytesRead == -1)
						throw new IOException("Client closed the connection before request was complete");

					// Track total bytes read from the wire
					totalBytesRead += bytesRead;

					// Flip the buffer to "read mode" so the decoder can consume bytes
					buffer.flip();

					// Decode bytes into chars. 'false' indicates we are not yet at the end of the input stream.
					// If the buffer ends with a partial multi-byte sequence, the decoder stops before it
					// and leaves those bytes in 'buffer' to be handled in the next iteration.
					decoder.decode(buffer, charBuffer, false);

					// Flip charBuffer to read the decoded characters out and append to builder
					charBuffer.flip();
					requestBuilder.append(charBuffer);

					// Reset charBuffer for the next pass
					charBuffer.clear();

					// Compact the buffer.
					// If the decoder didn't consume all bytes (because of a split character at the end),
					// compact() moves those remaining bytes to the start of the buffer and prepares
					// the rest of the buffer for the next socket read.
					buffer.compact();

					// Check size limit
					if (totalBytesRead > getMaximumRequestSizeInBytes()) {
						String rawRequest = requestBuilder.toString();

						// Given our partial raw request, try to parse it into a request...
						Request tooLargeRequest = parseTooLargeRequestForRawRequest(rawRequest).orElse(null);

						// ...if unable to parse into a request, bail
						if (tooLargeRequest == null)
							throw new IOException(format("Request is too large (exceeded %d bytes) but we do not have enough data available to know its path", getMaximumRequestSizeInBytes()));

						throw new RequestTooLargeIOException(format("Request too large (exceeded %d bytes)", getMaximumRequestSizeInBytes()), tooLargeRequest);
					}

					// Check if the headers are complete (CRLFCRLF preferred, else LFLF)
					// IMPORTANT: we may have read beyond the header terminator in the same read(),
					// so truncate to exactly the end of headers.
					int end = -1;
					int crlf = requestBuilder.indexOf("\r\n\r\n");

					if (crlf != -1) {
						end = crlf + 4;
					} else {
						int lf = requestBuilder.indexOf("\n\n");
						if (lf != -1) end = lf + 2;
					}

					if (end != -1) {
						requestBuilder.setLength(end);
						headersComplete = true;
					}
				}

				return requestBuilder.toString();
			});

			// Wait up to the specified timeout for reading to complete
			return readFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			// Time's up; cancel the task so the blocking read is interrupted
			if (readFuture != null)
				readFuture.cancel(true);

			throw new SocketTimeoutException(format("Reading request took longer than %d ms", timeoutMillis));
		} catch (InterruptedException e) {
			if (readFuture != null)
				readFuture.cancel(true);

			// Current thread interrupted while waiting
			Thread.currentThread().interrupt(); // restore interrupt status
			throw new IOException("Interrupted while awaiting request data", e);
		} catch (ExecutionException e) {
			if (readFuture != null)
				readFuture.cancel(true);

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
	@NonNull
	protected Optional<Request> parseTooLargeRequestForRawRequest(@NonNull String rawRequest) {
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

		HttpMethod httpMethod;

		try {
			httpMethod = HttpMethod.valueOf(rawHttpMethod);
		} catch (IllegalArgumentException e) {
			// Malformed HTTP method specified
			return Optional.empty();
		}

		String rawUri = parts[1];

		// Validate URI
		if (rawUri != null) {
			URI uri;

			try {
				uri = new URI(rawUri.trim());
			} catch (Exception e) {
				// Malformed URI specified
				return Optional.empty();
			}

			// Normalize absolute URIs to relative form
			String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
			String rawQuery = uri.getRawQuery();
			rawUri = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
		}

		// TODO: eventually would be nice to parse headers as best we can.  For now, we just parse the first request line
		return Optional.of(Request.withRawUrl(httpMethod, rawUri)
				.idGenerator(getIdGenerator())
				.contentTooLarge(true)
				.build());
	}

	@NotThreadSafe
	protected static class RequestTooLargeIOException extends IOException {
		@NonNull
		private final Request tooLargeRequest;

		public RequestTooLargeIOException(@Nullable String message,
																			@NonNull Request tooLargeRequest) {
			super(message);
			this.tooLargeRequest = requireNonNull(tooLargeRequest);
		}

		@NonNull
		public Request getTooLargeRequest() {
			return this.tooLargeRequest;
		}
	}

	@Override
	public void stop() {
		Thread eventLoopThreadSnapshot;
		ExecutorService requestHandlerExecutorServiceSnapshot;
		ScheduledExecutorService requestHandlerTimeoutExecutorServiceSnapshot;
		ExecutorService requestReaderExecutorServiceSnapshot;
		ServerSocketChannel serverSocketChannelSnapshot;
		boolean interrupted = false;

		getLock().lock();
		try {
			if (!this.started)
				return;

			this.stopping = true;
			getStopPoisonPill().set(true);

			eventLoopThreadSnapshot = this.eventLoopThread;
			requestHandlerExecutorServiceSnapshot = this.requestHandlerExecutorService;
			requestHandlerTimeoutExecutorServiceSnapshot = this.requestHandlerTimeoutExecutorService;
			requestReaderExecutorServiceSnapshot = this.requestReaderExecutorService;
			serverSocketChannelSnapshot = this.serverSocketChannel;
		} finally {
			getLock().unlock();
		}

		// Close server socket to unblock accept()
		if (serverSocketChannelSnapshot != null) {
			try {
				serverSocketChannelSnapshot.close();
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to close Server-Sent Event SocketChannel").throwable(e).build());
			}
		}

		List<DefaultServerSentEventConnection> connectionsSnapshot = new ArrayList<>(getGlobalConnections().keySet());
		for (DefaultServerSentEventConnection connection : connectionsSnapshot)
			connection.setTerminationReason(ServerSentEventConnection.TerminationReason.SERVER_STOP);

		// Close client connections - sends poison pills to all registered connections
		for (DefaultServerSentEventBroadcaster broadcaster : new ArrayList<>(getBroadcastersByResourcePath().values())) {
			try {
				broadcaster.unregisterAllServerSentEventConnections(true);
			} catch (Exception e) {
				safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Unable to shut down open Server-Sent Event connections")
						.throwable(e)
						.build());
			}
		}

		// Ensure nothing slipped through the cracks
		for (DefaultServerSentEventConnection connection : connectionsSnapshot) {
			try {
				connection.getClosing().compareAndSet(false, true);

				DefaultServerSentEventBroadcaster b = getGlobalConnections().get(connection);
				if (b != null) b.unregisterServerSentEventConnection(connection, true);

				connection.getSocketChannel().close();
			} catch (Throwable ignored) {
				// Nothing to do
			}
		}

		// Clear global connections map for sanity (though it should be empty by this point)
		getGlobalConnections().clear();

		// Use shutdownNow() immediately for request handlers.
		// Poison pills handle normal cases; interrupt handles edge cases where
		// a connection registered after we iterated above.
		if (requestHandlerExecutorServiceSnapshot != null)
			requestHandlerExecutorServiceSnapshot.shutdownNow();

		if (requestHandlerTimeoutExecutorServiceSnapshot != null)
			requestHandlerTimeoutExecutorServiceSnapshot.shutdownNow();

		if (requestReaderExecutorServiceSnapshot != null)
			requestReaderExecutorServiceSnapshot.shutdownNow();

		// Shared wall-clock deadline
		final long deadlineNanos = System.nanoTime() + getShutdownTimeout().toNanos();

		// Await the accept-loop thread and all executors **in parallel**
		long grace = Math.min(250L, remainingMillis(deadlineNanos));

		List<CompletableFuture<Boolean>> waits = new ArrayList<>(3);
		waits.add(joinAsync(eventLoopThreadSnapshot, grace));

		if (requestHandlerExecutorServiceSnapshot != null)
			waits.add(awaitTerminationAsync(requestHandlerExecutorServiceSnapshot, grace));

		if (requestHandlerTimeoutExecutorServiceSnapshot != null)
			waits.add(awaitTerminationAsync(requestHandlerTimeoutExecutorServiceSnapshot, grace));

		if (requestReaderExecutorServiceSnapshot != null)
			waits.add(awaitTerminationAsync(requestReaderExecutorServiceSnapshot, grace));

		// Wait for all, but no longer than the single budget
		try {
			CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).get(grace, TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			// Budget exhausted; escalate below
		} catch (InterruptedException e) {
			interrupted = true;
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			safelyLog(LogEvent.with(LogEventType.SERVER_SENT_EVENT_SERVER_INTERNAL_ERROR, "Exception while awaiting SSE shutdown").throwable(e).build());
		}

		// Escalate for any stragglers using remaining time
		hardenJoin(eventLoopThreadSnapshot, remainingMillis(deadlineNanos));

		// Pools already had shutdownNow() called; just await termination
		if (requestHandlerExecutorServiceSnapshot != null)
			awaitPoolTermination(requestHandlerExecutorServiceSnapshot, remainingMillis(deadlineNanos));

		if (requestHandlerTimeoutExecutorServiceSnapshot != null)
			awaitPoolTermination(requestHandlerTimeoutExecutorServiceSnapshot, remainingMillis(deadlineNanos));

		if (requestReaderExecutorServiceSnapshot != null)
			awaitPoolTermination(requestReaderExecutorServiceSnapshot, remainingMillis(deadlineNanos));

		getLock().lock();
		try {
			this.started = false;
			this.stopping = false; // allow future restarts
			this.eventLoopThread = null;
			this.serverSocketChannel = null;
			this.requestHandlerExecutorService = null;
			this.requestHandlerTimeoutExecutorService = null;
			this.requestReaderExecutorService = null;
			this.getBroadcastersByResourcePath().clear();
			this.getIdleBroadcastersByResourcePath().clear();
			this.getResourcePathDeclarationsByResourcePathCache().clear();
			this.activeConnectionCount.set(0);
			getStopPoisonPill().set(false);

			if (interrupted)
				Thread.currentThread().interrupt();
		} finally {
			getLock().unlock();
		}
	}

	private void awaitPoolTermination(@Nullable ExecutorService executorService,
																		@NonNull Long millis) {
		requireNonNull(millis);

		if (executorService == null || executorService.isTerminated())
			return;

		try {
			executorService.awaitTermination(Math.max(100L, millis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@NonNull
	protected CompletableFuture<Boolean> awaitTerminationAsync(@Nullable ExecutorService executorService,
																														 @NonNull Long millis) {
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

	@NonNull
	protected CompletableFuture<Boolean> joinAsync(@Nullable Thread thread,
																								 @NonNull Long millis) {
		requireNonNull(millis);

		if (thread == null || millis <= 0)
			return CompletableFuture.completedFuture(true);

		if (thread == Thread.currentThread())
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

	protected void hardenJoin(@Nullable Thread thread,
														@NonNull Long millis) {
		requireNonNull(millis);

		if (thread == null || !thread.isAlive() || thread == Thread.currentThread())
			return;

		thread.interrupt();

		try {
			thread.join(Math.max(100L, millis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@NonNull
	protected Long remainingMillis(@NonNull Long deadlineNanos) {
		requireNonNull(deadlineNanos);

		long rem = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
		return Math.max(0L, rem);
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		getLock().lock();

		try {
			return this.started;
		} finally {
			getLock().unlock();
		}
	}

	@NonNull
	protected Boolean isStopping() {
		getLock().lock();

		try {
			return this.stopping;
		} finally {
			getLock().unlock();
		}
	}

	@NonNull
	@Override
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		if (isStopping() || !isStarted())
			return Optional.empty();

		ResourceMethod resourceMethod = resourceMethodForResourcePath(resourcePath).orElse(null);

		if (resourceMethod == null)
			return Optional.empty();

		return Optional.of(new StableBroadcasterHandle(resourcePath));
	}

	@NonNull
	protected Optional<ResourceMethod> resourceMethodForResourcePath(@NonNull ResourcePath resourcePath) {
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

	@NonNull
	private DefaultServerSentEventBroadcaster newBroadcaster(@NonNull ResourceMethod resourceMethod,
																													 @NonNull ResourcePath resourcePath) {
		requireNonNull(resourceMethod);
		requireNonNull(resourcePath);

		BackpressureHandler handler = (owner, connection, cause) ->
				closeConnectionDueToBackpressure(owner, connection, cause);

		return new DefaultServerSentEventBroadcaster(
				resourceMethod,
				resourcePath,
				handler,
				(serverSentEventConnection) -> {
					// When the broadcaster unregisters a connection it manages, remove it from the global set as well
					DefaultServerSentEventBroadcaster removed = getGlobalConnections().remove(serverSentEventConnection);
					if (removed != null)
						releaseConnectionSlot();
				},
				this::safelyLog
		);
	}

	@NonNull
	private DefaultServerSentEventBroadcaster registerConnectionWithBroadcaster(
			@NonNull ResourcePath resourcePath,
			@NonNull ResourceMethod resourceMethod,
			@NonNull DefaultServerSentEventConnection connection) {
		requireNonNull(resourcePath);
		requireNonNull(resourceMethod);
		requireNonNull(connection);

		return getBroadcastersByResourcePath().compute(resourcePath, (rp, existing) -> {
			DefaultServerSentEventBroadcaster broadcaster =
					(existing != null) ? existing : newBroadcaster(resourceMethod, rp);

			// 1) Claim global slot FIRST (prevents "unregister before put" ghost entries)
			getGlobalConnections().put(connection, broadcaster);

			// 2) Only then make it visible to broadcasts
			boolean registered = broadcaster.registerServerSentEventConnection(connection);

			if (!registered) {
				// rollback global bookkeeping
				getGlobalConnections().remove(connection, broadcaster); // conditional remove is safer
				throw new IllegalStateException(format("Unable to register Server-Sent Event connection for %s", resourcePath));
			}

			return broadcaster;
		});
	}

	private boolean reserveConnectionSlot() {
		while (true) {
			int current = this.activeConnectionCount.get();

			if (current >= getConcurrentConnectionLimit())
				return false;

			if (this.activeConnectionCount.compareAndSet(current, current + 1))
				return true;
		}
	}

	private void releaseConnectionSlot() {
		this.activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
	}

	private void releaseReservedSlot(@NonNull AtomicBoolean slotReserved) {
		requireNonNull(slotReserved);

		if (slotReserved.getAndSet(false))
			releaseConnectionSlot();
	}

	@NonNull
	protected Optional<DefaultServerSentEventBroadcaster> acquireBroadcasterInternal(@NonNull ResourcePath resourcePath,
																																									 @NonNull ResourceMethod resourceMethod) {
		requireNonNull(resourcePath);
		requireNonNull(resourceMethod);

		DefaultServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
				.computeIfAbsent(resourcePath, (rp) -> newBroadcaster(resourceMethod, rp));

		// If no active connections, treat it as idle and put it in the idle LRU.
		// If it later becomes active, registration removes it from idle LRU.
		try {
			if (broadcaster.getServerSentEventConnections().isEmpty())
				getIdleBroadcastersByResourcePath().put(resourcePath, broadcaster);
			else
				getIdleBroadcastersByResourcePath().remove(resourcePath, broadcaster);
		} catch (Throwable ignored) {
			// best-effort; never fail acquire due to cache bookkeeping
		}

		return Optional.of(broadcaster);
	}

	protected void closeConnectionDueToBackpressure(@NonNull DefaultServerSentEventBroadcaster owner,
																									@NonNull DefaultServerSentEventConnection connection,
																									@NonNull String cause) {
		requireNonNull(owner);
		requireNonNull(connection);
		requireNonNull(cause);

		// Ensure only once
		if (!connection.getClosing().compareAndSet(false, true))
			return;

		connection.setTerminationReason(ServerSentEventConnection.TerminationReason.BACKPRESSURE);

		// String message = format("Closing Server-Sent Event connection due to backpressure (write queue at capacity) while enqueuing %s. {resourcePath=%s, queueSize=%d, remainingCapacity=%d}",
		//					cause, owner.getResourcePath(), writeQueue.size(), writeQueue.remainingCapacity());

		// Unregister from broadcaster (avoid enqueueing further)
		try {
			owner.unregisterServerSentEventConnection(connection, false);
		} catch (Throwable ignored) { /* best-effort */ }

		// Best effort to wake the consumer loop
		try {
			BlockingQueue<DefaultServerSentEventConnection.WriteQueueElement> writeQueue = connection.getWriteQueue();
			writeQueue.clear();
			writeQueue.offer(DefaultServerSentEventConnection.WriteQueueElement.poisonPill());
		} catch (Throwable ignored) { /* best-effort */ }

		// Force-close the channel to break any pending I/O
		try {
			SocketChannel socketChannel = connection.getSocketChannel();

			if (socketChannel != null)
				socketChannel.close();

		} catch (Throwable ignored) { /* channel may already be closed */ }
	}

	@NonNull
	protected Optional<ResourcePathDeclaration> matchingResourcePath(@Nullable ResourcePath resourcePath) {
		if (resourcePath == null)
			return Optional.empty();

		ResourcePathDeclaration resourcePathDeclaration = getResourcePathDeclarationsByResourcePathCache()
				.computeIfAbsent(resourcePath, rp -> {
					for (ResourcePathDeclaration d : getResourceMethodsByResourcePathDeclaration().keySet())
						if (d.matches(rp))
							return d;

					// Return sentinel instead of null to cache the negative result
					return NO_MATCH_SENTINEL;
				});

		// Convert sentinel back to empty Optional
		if (resourcePathDeclaration == NO_MATCH_SENTINEL)
			return Optional.empty();

		return Optional.of(resourcePathDeclaration);
	}

	protected void safelyLog(@NonNull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getLifecycleObserver().ifPresent(lifecycleObserver -> lifecycleObserver.didReceiveLogEvent(logEvent));
		} catch (Throwable throwable) {
			// The LifecycleObserver implementation errored out, but we can't let that affect us - swallow its exception.
			// Not much else we can do here but dump to stderr
			throwable.printStackTrace(System.err);
		}
	}

	protected void safelyCollectMetrics(@NonNull String message,
																			@Nullable Request request,
																			@Nullable ResourceMethod resourceMethod,
																			@NonNull Consumer<MetricsCollector> metricsConsumer) {
		requireNonNull(message);
		requireNonNull(metricsConsumer);

		MetricsCollector metricsCollector = this.metricsCollector;

		if (metricsCollector == null)
			return;

		try {
			metricsConsumer.accept(metricsCollector);
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED, message)
					.throwable(throwable)
					.request(request)
					.resourceMethod(resourceMethod)
					.build());
		}
	}

	@NonNull
	protected Integer getPort() {
		return this.port;
	}

	@NonNull
	protected String getHost() {
		return this.host;
	}

	@NonNull
	protected Duration getRequestTimeout() {
		return this.requestTimeout;
	}

	@NonNull
	protected Duration getRequestHandlerTimeout() {
		return this.requestHandlerTimeout;
	}

	@NonNull
	protected Duration getWriteTimeout() {
		return this.writeTimeout;
	}

	@NonNull
	protected Duration getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	@NonNull
	protected Duration getHeartbeatInterval() {
		return this.heartbeatInterval;
	}

	@NonNull
	protected Integer getMaximumRequestSizeInBytes() {
		return this.maximumRequestSizeInBytes;
	}

	@NonNull
	protected Integer getRequestReadBufferSizeInBytes() {
		return this.requestReadBufferSizeInBytes;
	}

	@NonNull
	public Map<@NonNull ResourcePathDeclaration, @NonNull ResourceMethod> getResourceMethodsByResourcePathDeclaration() {
		return this.resourceMethodsByResourcePathDeclaration;
	}

	// Package-private for test hook
	@NonNull
	ConcurrentHashMap<@NonNull ResourcePath, @NonNull DefaultServerSentEventBroadcaster> getBroadcastersByResourcePath() {
		return this.broadcastersByResourcePath;
	}

	@NonNull
	protected ConcurrentLruMap<@NonNull ResourcePath, @NonNull ResourcePathDeclaration> getResourcePathDeclarationsByResourcePathCache() {
		return this.resourcePathDeclarationsByResourcePathCache;
	}

	@NonNull
	protected Optional<ExecutorService> getRequestHandlerExecutorService() {
		return Optional.ofNullable(this.requestHandlerExecutorService);
	}

	@NonNull
	protected Optional<ScheduledExecutorService> getRequestHandlerTimeoutExecutorService() {
		return Optional.ofNullable(this.requestHandlerTimeoutExecutorService);
	}

	@NonNull
	protected Optional<ExecutorService> getRequestReaderExecutorService() {
		return Optional.ofNullable(this.requestReaderExecutorService);
	}

	@NonNull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@NonNull
	protected Supplier<ExecutorService> getRequestHandlerExecutorServiceSupplier() {
		return this.requestHandlerExecutorServiceSupplier;
	}

	@NonNull
	protected Supplier<ExecutorService> getRequestReaderExecutorServiceSupplier() {
		return this.requestReaderExecutorServiceSupplier;
	}

	@NonNull
	protected Integer getConcurrentConnectionLimit() {
		return this.concurrentConnectionLimit;
	}

	@NonNull
	protected Integer getConnectionQueueCapacity() {
		return this.connectionQueueCapacity;
	}

	@NonNull
	protected Boolean getVerifyConnectionOnceEstablished() {
		return this.verifyConnectionOnceEstablished;
	}

	@NonNull
	protected ConcurrentHashMap<@NonNull DefaultServerSentEventConnection, @NonNull DefaultServerSentEventBroadcaster> getGlobalConnections() {
		return this.globalConnections;
	}

	@NonNull
	protected Integer getActiveConnectionCount() {
		return this.activeConnectionCount.get();
	}

	@NonNull
	protected ConcurrentLruMap<@NonNull ResourcePath, @NonNull DefaultServerSentEventBroadcaster> getIdleBroadcastersByResourcePath() {
		return this.idleBroadcastersByResourcePath;
	}

	@NonNull
	protected AtomicBoolean getStopPoisonPill() {
		return this.stopPoisonPill;
	}

	@NonNull
	protected Optional<Thread> getEventLoopThread() {
		return Optional.ofNullable(this.eventLoopThread);
	}

	@NonNull
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}

	@NonNull
	protected Optional<LifecycleObserver> getLifecycleObserver() {
		return Optional.ofNullable(this.lifecycleObserver);
	}

	@NonNull
	protected Optional<MetricsCollector> getMetricsCollector() {
		return Optional.ofNullable(this.metricsCollector);
	}

	@NonNull
	protected Optional<ResponseMarshaler> getResponseMarshaler() {
		return Optional.ofNullable(this.responseMarshaler);
	}

	@Nullable
	private IdGenerator<?> getIdGenerator() {
		return this.idGenerator;
	}
}
